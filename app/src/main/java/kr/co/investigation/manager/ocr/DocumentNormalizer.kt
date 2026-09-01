package kr.co.investigation.manager.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max

/**
 * OCR 전용 문서 보정기.
 * 증거자료인 원본 파일은 절대 수정하지 않고 메모리 복사본만 처리한다.
 */
object DocumentNormalizer {
    data class Result(
        val bitmap: Bitmap,
        val documentDetected: Boolean,
        val message: String
    )

    // OCR용 이미지는 A4 300dpi 상당으로 정규화한다. 원본 증거파일은 변경하지 않는다.
    private const val A4_WIDTH = 2480
    private const val A4_HEIGHT = 3508

    suspend fun normalize(context: Context, uri: Uri): Result = withContext(Dispatchers.Default) {
        val srcBitmap = loadBitmap(context, uri)
        if (!OpenCVLoader.initLocal()) {
            return@withContext Result(srcBitmap, false, "OpenCV 초기화 실패 - 보정 없이 OCR")
        }

        val working = downscaleForDetection(srcBitmap, 3000)
        val rgba = Mat()
        Utils.bitmapToMat(working, rgba)

        val gray = Mat()
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)

        val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
        val enhanced = Mat()
        clahe.apply(gray, enhanced)

        val edges = Mat()
        Imgproc.Canny(enhanced, edges, 55.0, 170.0)
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
        Imgproc.morphologyEx(edges, edges, Imgproc.MORPH_CLOSE, kernel)

        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)

        val page = contours
            .asSequence()
            .sortedByDescending { Imgproc.contourArea(it) }
            .mapNotNull { contour ->
                val area = Imgproc.contourArea(contour)
                if (area < working.width * working.height * 0.18) return@mapNotNull null
                val curve = MatOfPoint2f(*contour.toArray())
                val peri = Imgproc.arcLength(curve, true)
                val approx = MatOfPoint2f()
                Imgproc.approxPolyDP(curve, approx, 0.02 * peri, true)
                val points = approx.toArray()
                if (points.size == 4 && Imgproc.isContourConvex(MatOfPoint(*points))) points else null
            }
            .firstOrNull()

        if (page == null) {
            releaseAll(gray, enhanced, edges, kernel, hierarchy, rgba)
            return@withContext Result(working, false, "문서 외곽 자동 인식 실패 - 보정 없이 OCR")
        }

        val ordered = orderPoints(page)
        val src = MatOfPoint2f(*ordered)
        val dst = MatOfPoint2f(
            Point(0.0, 0.0),
            Point((A4_WIDTH - 1).toDouble(), 0.0),
            Point((A4_WIDTH - 1).toDouble(), (A4_HEIGHT - 1).toDouble()),
            Point(0.0, (A4_HEIGHT - 1).toDouble())
        )
        val transform = Imgproc.getPerspectiveTransform(src, dst)
        val warped = Mat(A4_HEIGHT, A4_WIDTH, CvType.CV_8UC4)
        Imgproc.warpPerspective(
            rgba,
            warped,
            transform,
            Size(A4_WIDTH.toDouble(), A4_HEIGHT.toDouble()),
            Imgproc.INTER_CUBIC,
            Core.BORDER_CONSTANT,
            Scalar(255.0, 255.0, 255.0, 255.0)
        )

        val out = Bitmap.createBitmap(A4_WIDTH, A4_HEIGHT, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(warped, out)
        releaseAll(gray, enhanced, edges, kernel, hierarchy, rgba, src, dst, transform, warped)
        Result(out, true, "문서 외곽/원근 보정 완료 (A4 300dpi OCR용)")
    }

    private fun loadBitmap(context: Context, uri: Uri): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        val maxDecodeSide = 4200
        while (max(bounds.outWidth, bounds.outHeight) / sample > maxDecodeSide) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample.coerceAtLeast(1)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        context.contentResolver.openInputStream(uri).use { input ->
            return BitmapFactory.decodeStream(input, null, opts)
                ?: error("이미지를 읽을 수 없습니다.")
        }
    }

    private fun downscaleForDetection(src: Bitmap, maxSide: Int): Bitmap {
        val side = max(src.width, src.height)
        if (side <= maxSide) return src
        val ratio = maxSide.toDouble() / side.toDouble()
        val w = (src.width * ratio).toInt().coerceAtLeast(1)
        val h = (src.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, w, h, true)
    }

    private fun orderPoints(points: Array<Point>): Array<Point> {
        val tl = points.minBy { it.x + it.y }
        val br = points.maxBy { it.x + it.y }
        val tr = points.minBy { it.y - it.x }
        val bl = points.maxBy { it.y - it.x }
        return arrayOf(tl, tr, br, bl)
    }

    private fun releaseAll(vararg mats: Mat) {
        mats.forEach { runCatching { it.release() } }
    }
}

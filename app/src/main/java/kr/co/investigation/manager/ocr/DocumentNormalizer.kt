package kr.co.investigation.manager.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
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
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * OCR 전용 문서 보정기.
 * 증거자료인 원본 파일은 절대 수정하지 않고 메모리 복사본만 처리한다.
 *
 * v0.8:
 * - 카메라/갤러리 EXIF 회전값 반영
 * - 가장 큰 사각형만 고르는 대신 면적·A4 종횡비·사각형 품질을 함께 평가
 * - 문서 테두리가 일부 끊겨도 minAreaRect를 이용한 보조 검출
 */
object DocumentNormalizer {
    data class Result(
        val bitmap: Bitmap,
        val documentDetected: Boolean,
        val message: String
    )

    private data class QuadCandidate(val points: Array<Point>, val score: Double)

    // OCR 메모리 이미지. 원본 파일 자체는 이 크기로 바꾸지 않는다.
    private const val A4_WIDTH = 2480
    private const val A4_HEIGHT = 3508
    private const val A4_RATIO = 1.4142

    suspend fun normalize(context: Context, uri: Uri): Result = withContext(Dispatchers.Default) {
        val srcBitmap = loadBitmapWithExif(context, uri)
        if (!OpenCVLoader.initLocal()) {
            return@withContext Result(srcBitmap, false, "OpenCV 초기화 실패 - EXIF 회전만 적용")
        }

        val working = downscaleForDetection(srcBitmap, 3200)
        val rgba = Mat()
        Utils.bitmapToMat(working, rgba)

        val gray = Mat()
        val enhanced = Mat()
        val edges = Mat()
        val kernel = Mat()
        val hierarchy = Mat()
        val contours = mutableListOf<MatOfPoint>()

        try {
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)

            val clahe = Imgproc.createCLAHE(2.2, Size(8.0, 8.0))
            clahe.apply(gray, enhanced)

            // 조명이 어둡거나 흰 종이 경계가 약한 사진을 위해 Canny 임계값을 너무 높게 두지 않는다.
            Imgproc.Canny(enhanced, edges, 42.0, 145.0)
            val closeKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(7.0, 7.0))
            closeKernel.copyTo(kernel)
            Imgproc.morphologyEx(edges, edges, Imgproc.MORPH_CLOSE, kernel)
            Imgproc.dilate(edges, edges, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0)))

            Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)

            val frameArea = working.width.toDouble() * working.height.toDouble()
            val candidates = mutableListOf<QuadCandidate>()

            for (contour in contours.sortedByDescending { Imgproc.contourArea(it) }.take(80)) {
                val area = Imgproc.contourArea(contour)
                val areaFraction = area / frameArea
                if (areaFraction < 0.12) continue

                val curve = MatOfPoint2f(*contour.toArray())
                try {
                    val peri = Imgproc.arcLength(curve, true)
                    for (epsilon in listOf(0.015, 0.02, 0.028, 0.04)) {
                        val approx = MatOfPoint2f()
                        try {
                            Imgproc.approxPolyDP(curve, approx, epsilon * peri, true)
                            val pts = approx.toArray()
                            if (pts.size == 4 && Imgproc.isContourConvex(MatOfPoint(*pts))) {
                                val ordered = orderPoints(pts)
                                val score = scoreQuad(ordered, areaFraction)
                                if (score > 0.0) candidates += QuadCandidate(ordered, score)
                            }
                        } finally {
                            approx.release()
                        }
                    }
                } finally {
                    curve.release()
                }
            }

            var page = candidates.maxByOrNull { it.score }?.points
            var fallbackUsed = false

            // 사진에서 종이 가장자리가 일부 잘리거나 그림자로 끊긴 경우의 보조 검출.
            if (page == null) {
                val largest = contours.maxByOrNull { Imgproc.contourArea(it) }
                if (largest != null && Imgproc.contourArea(largest) / frameArea >= 0.16) {
                    val curve = MatOfPoint2f(*largest.toArray())
                    try {
                        val rr = Imgproc.minAreaRect(curve)
                        val pts = Array(4) { Point() }
                        rr.points(pts)
                        val ordered = orderPoints(pts)
                        if (scoreQuad(ordered, Imgproc.contourArea(largest) / frameArea) > 0.0) {
                            page = ordered
                            fallbackUsed = true
                        }
                    } finally {
                        curve.release()
                    }
                }
            }

            if (page == null) {
                return@withContext Result(
                    working,
                    false,
                    "문서 외곽 자동 인식 실패 - EXIF 회전/전체 이미지 OCR"
                )
            }

            val src = MatOfPoint2f(*page)
            val dst = MatOfPoint2f(
                Point(0.0, 0.0),
                Point((A4_WIDTH - 1).toDouble(), 0.0),
                Point((A4_WIDTH - 1).toDouble(), (A4_HEIGHT - 1).toDouble()),
                Point(0.0, (A4_HEIGHT - 1).toDouble())
            )
            val transform = Imgproc.getPerspectiveTransform(src, dst)
            val warped = Mat(A4_HEIGHT, A4_WIDTH, CvType.CV_8UC4)

            try {
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
                Result(
                    out,
                    true,
                    if (fallbackUsed) "문서 보조검출/원근 보정 완료" else "문서 외곽/원근 보정 완료"
                )
            } finally {
                src.release(); dst.release(); transform.release(); warped.release()
            }
        } finally {
            gray.release(); enhanced.release(); edges.release(); kernel.release(); hierarchy.release(); rgba.release()
            contours.forEach { runCatching { it.release() } }
        }
    }

    /** A4에 가까운 큰 사각형일수록 높은 점수. 표 내부의 큰 사각형 오검출을 줄인다. */
    private fun scoreQuad(points: Array<Point>, areaFraction: Double): Double {
        if (points.size != 4) return -1.0
        val top = distance(points[0], points[1])
        val right = distance(points[1], points[2])
        val bottom = distance(points[2], points[3])
        val left = distance(points[3], points[0])
        val width = (top + bottom) / 2.0
        val height = (left + right) / 2.0
        if (width < 80 || height < 120) return -1.0

        val ratio = max(width, height) / min(width, height).coerceAtLeast(1.0)
        if (ratio !in 1.05..2.25) return -1.0
        val aspectScore = (1.0 - abs(ratio - A4_RATIO) / 0.9).coerceIn(0.0, 1.0)

        val oppositeBalance = (
            1.0 - (abs(top - bottom) / max(top, bottom).coerceAtLeast(1.0) +
                abs(left - right) / max(left, right).coerceAtLeast(1.0)) / 2.0
            ).coerceIn(0.0, 1.0)

        // 면적 비중을 가장 크게 줘서 문서 내부의 표 테두리보다 종이 외곽을 우선한다.
        return areaFraction.coerceIn(0.0, 1.0) * 6.0 + aspectScore * 2.2 + oppositeBalance * 0.8
    }

    private fun distance(a: Point, b: Point): Double = hypot(a.x - b.x, a.y - b.y)

    private fun loadBitmapWithExif(context: Context, uri: Uri): Bitmap {
        val orientation = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                ExifInterface(input).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        val maxDecodeSide = 4400
        while (max(bounds.outWidth, bounds.outHeight) / sample > maxDecodeSide) sample *= 2

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample.coerceAtLeast(1)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = context.contentResolver.openInputStream(uri).use { input ->
            BitmapFactory.decodeStream(input, null, opts)
                ?: error("이미지를 읽을 수 없습니다.")
        }

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f); matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(-90f); matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        }

        if (matrix.isIdentity) return decoded
        val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (rotated !== decoded) decoded.recycle()
        return rotated
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
}

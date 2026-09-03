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

/** OCR 전용 문서 정렬기. 증거 원본 파일은 수정하지 않고 메모리 Bitmap만 처리한다. */
object DocumentNormalizer {
    data class Result(
        val bitmap: Bitmap,
        val documentDetected: Boolean,
        val message: String
    )

    private data class QuadCandidate(
        val points: Array<Point>,
        val areaFraction: Double,
        val ratio: Double,
        val centerY: Double,
        val widthFraction: Double
    )

    private const val A4_WIDTH = 2480
    private const val A4_HEIGHT = 3508
    private const val A4_RATIO = 1.4142

    // 제공된 실제 양식에서 2.의뢰내용 + 임차인표 전체 외곽을 기준으로 만든 canonical 위치.
    // 종이 테두리가 사진 프레임 밖으로 잘려도 이 표는 거의 항상 보이므로 매우 안정적인 기준점이다.
    private const val ANCHOR_LEFT = 200.0
    private const val ANCHOR_TOP = 1170.0
    private const val ANCHOR_RIGHT = 2370.0
    private const val ANCHOR_BOTTOM = 2240.0

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
        val hierarchy = Mat()
        val contours = mutableListOf<MatOfPoint>()
        try {
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)
            Imgproc.createCLAHE(2.2, Size(8.0, 8.0)).apply(gray, enhanced)
            Imgproc.Canny(enhanced, edges, 42.0, 145.0)
            Imgproc.morphologyEx(
                edges,
                edges,
                Imgproc.MORPH_CLOSE,
                Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(7.0, 7.0))
            )
            Imgproc.dilate(
                edges,
                edges,
                Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
            )
            Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)

            val frameArea = working.width.toDouble() * working.height.toDouble()
            val quads = mutableListOf<QuadCandidate>()
            for (contour in contours.sortedByDescending { Imgproc.contourArea(it) }.take(100)) {
                val area = Imgproc.contourArea(contour)
                val areaFraction = area / frameArea
                if (areaFraction < 0.08) continue
                val curve = MatOfPoint2f(*contour.toArray())
                try {
                    val peri = Imgproc.arcLength(curve, true)
                    for (epsilon in listOf(0.012, 0.018, 0.025, 0.035, 0.045)) {
                        val approx = MatOfPoint2f()
                        try {
                            Imgproc.approxPolyDP(curve, approx, epsilon * peri, true)
                            val pts = approx.toArray()
                            if (pts.size != 4) continue
                            val poly = MatOfPoint(*pts)
                            val convex = try { Imgproc.isContourConvex(poly) } finally { poly.release() }
                            if (!convex) continue
                            val ordered = orderPoints(pts)
                            metrics(ordered, areaFraction, working.width, working.height)?.let(quads::add)
                        } finally {
                            approx.release()
                        }
                    }
                } finally {
                    curve.release()
                }
            }

            // 1) 진짜 종이 외곽: A4 종횡비에 가깝고 충분히 큰 사각형만 허용한다.
            // v0.9 문제의 원인이었던 중앙 표(비율 약 2.2)는 여기서 절대 종이로 선택되지 않는다.
            val page = quads
                .filter { it.areaFraction >= 0.28 && it.ratio in 1.16..1.78 }
                .maxByOrNull { pageScore(it) }

            // 2) 종이 가장자리가 사진 밖으로 잘린 경우: 고정 양식 중앙 대형 표를 기준으로 정렬한다.
            // 실제 제공 사진에서 이 표는 프레임의 약 26%, 가로폭 약 88%, 비율 약 2.2이다.
            val tableAnchor = quads
                .filter {
                    it.areaFraction in 0.15..0.48 &&
                        it.ratio in 1.75..2.65 &&
                        it.widthFraction >= 0.66 &&
                        it.centerY in 0.34..0.72
                }
                .maxByOrNull { anchorScore(it) }

            return@withContext when {
                page != null && (page.areaFraction >= 0.46 || tableAnchor == null) -> {
                    warp(
                        rgba,
                        page.points,
                        arrayOf(
                            Point(0.0, 0.0),
                            Point((A4_WIDTH - 1).toDouble(), 0.0),
                            Point((A4_WIDTH - 1).toDouble(), (A4_HEIGHT - 1).toDouble()),
                            Point(0.0, (A4_HEIGHT - 1).toDouble())
                        ),
                        "문서 외곽/원근 보정 완료"
                    )
                }
                tableAnchor != null -> {
                    warp(
                        rgba,
                        tableAnchor.points,
                        arrayOf(
                            Point(ANCHOR_LEFT, ANCHOR_TOP),
                            Point(ANCHOR_RIGHT, ANCHOR_TOP),
                            Point(ANCHOR_RIGHT, ANCHOR_BOTTOM),
                            Point(ANCHOR_LEFT, ANCHOR_BOTTOM)
                        ),
                        "고정양식 중앙표 기준 정렬 완료"
                    )
                }
                page != null -> {
                    warp(
                        rgba,
                        page.points,
                        arrayOf(
                            Point(0.0, 0.0),
                            Point((A4_WIDTH - 1).toDouble(), 0.0),
                            Point((A4_WIDTH - 1).toDouble(), (A4_HEIGHT - 1).toDouble()),
                            Point(0.0, (A4_HEIGHT - 1).toDouble())
                        ),
                        "문서 외곽/원근 보정 완료"
                    )
                }
                else -> Result(working, false, "고정양식 기준점 검출 실패 - 전체 이미지 OCR")
            }
        } finally {
            gray.release(); enhanced.release(); edges.release(); hierarchy.release(); rgba.release()
            contours.forEach { runCatching { it.release() } }
        }
    }

    private fun warp(source: Mat, from: Array<Point>, to: Array<Point>, message: String): Result {
        val src = MatOfPoint2f(*from)
        val dst = MatOfPoint2f(*to)
        val transform = Imgproc.getPerspectiveTransform(src, dst)
        val warped = Mat(A4_HEIGHT, A4_WIDTH, CvType.CV_8UC4)
        return try {
            Imgproc.warpPerspective(
                source,
                warped,
                transform,
                Size(A4_WIDTH.toDouble(), A4_HEIGHT.toDouble()),
                Imgproc.INTER_CUBIC,
                Core.BORDER_CONSTANT,
                Scalar(255.0, 255.0, 255.0, 255.0)
            )
            val out = Bitmap.createBitmap(A4_WIDTH, A4_HEIGHT, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(warped, out)
            Result(out, true, message)
        } finally {
            src.release(); dst.release(); transform.release(); warped.release()
        }
    }

    private fun metrics(
        points: Array<Point>,
        areaFraction: Double,
        frameWidth: Int,
        frameHeight: Int
    ): QuadCandidate? {
        val top = distance(points[0], points[1])
        val right = distance(points[1], points[2])
        val bottom = distance(points[2], points[3])
        val left = distance(points[3], points[0])
        val width = (top + bottom) / 2.0
        val height = (left + right) / 2.0
        if (width < 80 || height < 80) return null
        val ratio = max(width, height) / min(width, height).coerceAtLeast(1.0)
        val centerY = points.map { it.y }.average() / frameHeight.toDouble()
        val widthFraction = width / frameWidth.toDouble()
        return QuadCandidate(points, areaFraction, ratio, centerY, widthFraction)
    }

    private fun pageScore(c: QuadCandidate): Double {
        val aspect = (1.0 - abs(c.ratio - A4_RATIO) / 0.45).coerceIn(0.0, 1.0)
        return c.areaFraction * 8.0 + aspect * 3.0 + c.widthFraction
    }

    private fun anchorScore(c: QuadCandidate): Double {
        val ratioScore = (1.0 - abs(c.ratio - 2.15) / 0.65).coerceIn(0.0, 1.0)
        val centerScore = (1.0 - abs(c.centerY - 0.49) / 0.28).coerceIn(0.0, 1.0)
        return c.areaFraction * 5.0 + c.widthFraction * 3.0 + ratioScore * 2.0 + centerScore
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
            BitmapFactory.decodeStream(input, null, opts) ?: error("이미지를 읽을 수 없습니다.")
        }

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(-90f); matrix.postScale(-1f, 1f) }
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
        return Bitmap.createScaledBitmap(
            src,
            (src.width * ratio).toInt().coerceAtLeast(1),
            (src.height * ratio).toInt().coerceAtLeast(1),
            true
        )
    }

    private fun orderPoints(points: Array<Point>): Array<Point> {
        val tl = points.minBy { it.x + it.y }
        val br = points.maxBy { it.x + it.y }
        val tr = points.minBy { it.y - it.x }
        val bl = points.maxBy { it.y - it.x }
        return arrayOf(tl, tr, br, bl)
    }
}

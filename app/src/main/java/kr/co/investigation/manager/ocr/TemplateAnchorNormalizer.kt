package kr.co.investigation.manager.ocr

import android.graphics.Bitmap
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

/**
 * 실제 조사의뢰서 사진에서 인쇄된 중앙 표를 최종 기준점으로 다시 맞춘다.
 *
 * DocumentNormalizer가 종이 외곽을 매우 잘 찾은 사진도 인쇄 위치/여백 차이 때문에
 * 고정 셀 좌표와 100~200px 정도 어긋날 수 있다. 또한 구김/빛반사 때문에 사각형
 * contour가 끊긴 사진은 기존 기준점 검출이 실패할 수 있다.
 *
 * 이 단계는 글자를 보지 않고 표의 긴 가로/세로 선만 사용한다. 원본 파일은 수정하지
 * 않으며, 필요할 때에만 메모리 Bitmap을 새로 만든다.
 */
object TemplateAnchorNormalizer {
    private const val A4_WIDTH = 2480
    private const val A4_HEIGHT = 3508

    private val canonical = arrayOf(
        Point(200.0, 1170.0),
        Point(2370.0, 1170.0),
        Point(2370.0, 2240.0),
        Point(200.0, 2240.0)
    )

    fun realign(source: DocumentNormalizer.Result): DocumentNormalizer.Result {
        val deskewed = DocumentSkewCorrector.correct(source)
        var result: DocumentNormalizer.Result? = null
        try {
            return realignTable(deskewed).also { result = it }
        } finally {
            if (deskewed.bitmap !== source.bitmap && deskewed.bitmap !== result?.bitmap) deskewed.bitmap.recycle()
        }
    }

    private fun realignTable(source: DocumentNormalizer.Result): DocumentNormalizer.Result {
        if (!OpenCVLoader.initLocal()) return source
        if (source.bitmap.width < 1100 || source.bitmap.height < 1500) return source

        val rgba = Mat()
        val gray = Mat()
        val binary = Mat()
        val horizontal = Mat()
        val vertical = Mat()
        val grid = Mat()
        val connected = Mat()
        val hierarchy = Mat()
        val contours = mutableListOf<MatOfPoint>()

        return try {
            Utils.bitmapToMat(source.bitmap, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.adaptiveThreshold(
                gray,
                binary,
                255.0,
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                Imgproc.THRESH_BINARY_INV,
                31,
                15.0
            )

            val horizontalKernel = Imgproc.getStructuringElement(
                Imgproc.MORPH_RECT,
                Size(max(40, source.bitmap.width / 12).toDouble(), 1.0)
            )
            val verticalKernel = Imgproc.getStructuringElement(
                Imgproc.MORPH_RECT,
                Size(1.0, max(40, source.bitmap.height / 24).toDouble())
            )
            val connectKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(7.0, 7.0))

            try {
                Imgproc.morphologyEx(binary, horizontal, Imgproc.MORPH_OPEN, horizontalKernel)
                Imgproc.morphologyEx(binary, vertical, Imgproc.MORPH_OPEN, verticalKernel)
                Core.bitwise_or(horizontal, vertical, grid)
                Imgproc.dilate(grid, connected, connectKernel)
            } finally {
                horizontalKernel.release()
                verticalKernel.release()
                connectKernel.release()
            }

            Imgproc.findContours(
                connected,
                contours,
                hierarchy,
                Imgproc.RETR_EXTERNAL,
                Imgproc.CHAIN_APPROX_SIMPLE
            )

            val frameWidth = source.bitmap.width.toDouble()
            val frameHeight = source.bitmap.height.toDouble()
            var bestPoints: Array<Point>? = null
            var bestScore = Double.NEGATIVE_INFINITY

            contours.forEach { contour ->
                val rect = Imgproc.boundingRect(contour)
                val widthFraction = rect.width / frameWidth
                val heightFraction = rect.height / frameHeight
                val centerY = (rect.y + rect.height / 2.0) / frameHeight
                if (widthFraction < 0.60) return@forEach
                if (heightFraction !in 0.18..0.50) return@forEach
                if (centerY !in 0.32..0.70) return@forEach

                val curve = MatOfPoint2f(*contour.toArray())
                try {
                    // A rotated rectangle removes roll but cannot remove perspective.
                    // Keep the measured four corners of the convex table outline.
                    val hullIndices = org.opencv.core.MatOfInt()
                    val hull = MatOfPoint2f()
                    val approx = MatOfPoint2f()
                    val ordered = try {
                        Imgproc.convexHull(contour, hullIndices)
                        val all = contour.toArray()
                        hull.fromArray(*hullIndices.toArray().map { all[it] }.toTypedArray())
                        var corners: Array<Point>? = null
                        for (epsilon in listOf(0.01, 0.02, 0.03, 0.04)) {
                            Imgproc.approxPolyDP(hull, approx, epsilon * Imgproc.arcLength(hull, true), true)
                            if (approx.total() == 4L) { corners = orderPoints(approx.toArray()); break }
                        }
                        corners ?: return@forEach
                    } finally { hullIndices.release(); hull.release(); approx.release() }
                    val tableWidth = (distance(ordered[0], ordered[1]) + distance(ordered[3], ordered[2])) / 2.0
                    val tableHeight = (distance(ordered[0], ordered[3]) + distance(ordered[1], ordered[2])) / 2.0
                    if (tableWidth < 300.0 || tableHeight < 200.0) return@forEach
                    val ratio = tableWidth / tableHeight.coerceAtLeast(1.0)
                    if (ratio !in 1.65..2.65) return@forEach

                    val ratioScore = (1.0 - abs(ratio - 2.05) / 0.70).coerceIn(0.0, 1.0)
                    val centerScore = (1.0 - abs(centerY - 0.50) / 0.25).coerceIn(0.0, 1.0)
                    val score = widthFraction * 4.0 + heightFraction * 2.0 + ratioScore * 2.0 + centerScore
                    if (score > bestScore) {
                        bestScore = score
                        bestPoints = ordered
                    }
                } finally {
                    curve.release()
                }
            }

            val detected = bestPoints ?: return source
            if (
                source.bitmap.width == A4_WIDTH &&
                source.bitmap.height == A4_HEIGHT &&
                meanCornerDistance(detected, canonical) <= 35.0
            ) {
                return source
            }

            val src = MatOfPoint2f(*detected)
            val dst = MatOfPoint2f(*canonical)
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
                DocumentNormalizer.Result(
                    bitmap = out,
                    documentDetected = true,
                    message = source.message + " / 중앙표 선 기반 정밀 재정렬 완료"
                )
            } finally {
                src.release()
                dst.release()
                transform.release()
                warped.release()
            }
        } finally {
            rgba.release()
            gray.release()
            binary.release()
            horizontal.release()
            vertical.release()
            grid.release()
            connected.release()
            hierarchy.release()
            contours.forEach { runCatching { it.release() } }
        }
    }

    private fun meanCornerDistance(a: Array<Point>, b: Array<Point>): Double =
        a.indices.sumOf { distance(a[it], b[it]) } / a.size.toDouble()

    private fun distance(a: Point, b: Point): Double = hypot(a.x - b.x, a.y - b.y)

    private fun orderPoints(points: Array<Point>): Array<Point> {
        val tl = points.minBy { it.x + it.y }
        val br = points.maxBy { it.x + it.y }
        val tr = points.minBy { it.y - it.x }
        val bl = points.maxBy { it.y - it.x }
        return arrayOf(tl, tr, br, bl)
    }
}

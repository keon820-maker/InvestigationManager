package kr.co.investigation.manager.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min

/**
 * 원근 보정된 조사의뢰서에서 표의 실제 셀 경계를 검출한다.
 * 고정 좌표로 값을 자르는 대신, 인쇄된 표 선을 기준으로 셀을 찾기 때문에
 * 촬영 위치/각도/크기 변화에 더 강하다.
 */
object TableCellDetector {
    fun detect(bitmap: Bitmap): List<Rect> {
        if (!OpenCVLoader.initLocal()) return emptyList()

        val rgba = Mat()
        val gray = Mat()
        val bw = Mat()
        val horizontal = Mat()
        val vertical = Mat()
        val grid = Mat()
        val hierarchy = Mat()
        val contours = mutableListOf<MatOfPoint>()

        try {
            Utils.bitmapToMat(bitmap, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.adaptiveThreshold(
                gray, bw, 255.0,
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                Imgproc.THRESH_BINARY_INV,
                35, 13.0
            )

            val hk = Imgproc.getStructuringElement(
                Imgproc.MORPH_RECT,
                Size(max(24, bitmap.width / 28).toDouble(), 1.0)
            )
            val vk = Imgproc.getStructuringElement(
                Imgproc.MORPH_RECT,
                Size(1.0, max(24, bitmap.height / 75).toDouble())
            )
            Imgproc.morphologyEx(bw, horizontal, Imgproc.MORPH_OPEN, hk)
            Imgproc.morphologyEx(bw, vertical, Imgproc.MORPH_OPEN, vk)
            Core.add(horizontal, vertical, grid)

            val closeKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
            Imgproc.morphologyEx(grid, grid, Imgproc.MORPH_CLOSE, closeKernel)
            Imgproc.dilate(grid, grid, closeKernel)

            Imgproc.findContours(grid, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)

            val w = bitmap.width
            val h = bitmap.height
            val minY = (h * 0.18).toInt()
            val maxY = (h * 0.72).toInt()

            val raw = contours.map { Imgproc.boundingRect(it) }
                .map { Rect(it.x, it.y, it.x + it.width, it.y + it.height) }
                .filter { r ->
                    r.top in minY..maxY &&
                        r.width() >= w * 0.045 &&
                        r.height() >= h * 0.012 &&
                        r.height() <= h * 0.12 &&
                        r.width() <= w * 0.94 &&
                        r.left >= w * 0.025 && r.right <= w * 0.985
                }

            val deduped = mutableListOf<Rect>()
            for (r in raw.sortedBy { it.width() * it.height() }) {
                val duplicate = deduped.any { d -> overlapRatio(r, d) > 0.90 }
                if (!duplicate) deduped += r
            }

            // 큰 외곽 행/표 사각형은 제거하고 실제 셀 위주로 남긴다.
            val leaf = deduped.filter { outer ->
                deduped.none { inner ->
                    inner !== outer &&
                        containsWithMargin(outer, inner, 3) &&
                        area(inner) < area(outer) * 0.72
                }
            }

            return leaf.sortedWith(compareBy<Rect> { it.top }.thenBy { it.left })
        } finally {
            rgba.release(); gray.release(); bw.release(); horizontal.release(); vertical.release();
            grid.release(); hierarchy.release(); contours.forEach { it.release() }
        }
    }

    fun prepareCrop(bitmap: Bitmap, rect: Rect): Bitmap {
        val insetX = max(5, rect.width() / 45)
        val insetY = max(4, rect.height() / 18)
        val l = (rect.left + insetX).coerceIn(0, bitmap.width - 1)
        val t = (rect.top + insetY).coerceIn(0, bitmap.height - 1)
        val r = (rect.right - insetX).coerceIn(l + 1, bitmap.width)
        val b = (rect.bottom - insetY).coerceIn(t + 1, bitmap.height)
        val crop = Bitmap.createBitmap(bitmap, l, t, r - l, b - t)

        if (!OpenCVLoader.initLocal()) return crop

        val rgba = Mat()
        val gray = Mat()
        val enhanced = Mat()
        try {
            Utils.bitmapToMat(crop, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            val clahe = Imgproc.createCLAHE(2.6, Size(8.0, 8.0))
            clahe.apply(gray, enhanced)

            val targetHeight = max(150, crop.height * 3)
            val scale = targetHeight.toDouble() / crop.height.toDouble()
            val resized = Mat()
            Imgproc.resize(
                enhanced,
                resized,
                Size(max(1.0, crop.width * scale), targetHeight.toDouble()),
                0.0, 0.0, Imgproc.INTER_CUBIC
            )
            Imgproc.copyMakeBorder(resized, resized, 24, 24, 24, 24, Core.BORDER_CONSTANT, org.opencv.core.Scalar(255.0))
            val out = Bitmap.createBitmap(resized.cols(), resized.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(resized, out)
            resized.release()
            crop.recycle()
            return out
        } finally {
            rgba.release(); gray.release(); enhanced.release()
        }
    }

    private fun area(r: Rect): Double = r.width().toDouble() * r.height().toDouble()

    private fun containsWithMargin(outer: Rect, inner: Rect, margin: Int): Boolean =
        inner.left >= outer.left + margin && inner.top >= outer.top + margin &&
            inner.right <= outer.right - margin && inner.bottom <= outer.bottom - margin

    private fun overlapRatio(a: Rect, b: Rect): Double {
        val l = max(a.left, b.left)
        val t = max(a.top, b.top)
        val r = min(a.right, b.right)
        val bot = min(a.bottom, b.bottom)
        if (r <= l || bot <= t) return 0.0
        val inter = (r - l).toDouble() * (bot - t).toDouble()
        return inter / min(area(a), area(b)).coerceAtLeast(1.0)
    }
}

package kr.co.investigation.manager.ocr

import android.graphics.Bitmap
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.atan2

/** Remove residual print skew before the axis-aligned grid morphology. No text is read. */
internal object DocumentSkewCorrector {
    fun correct(source: DocumentNormalizer.Result): DocumentNormalizer.Result {
        if (!OpenCVLoader.initLocal()) return source
        val rgba = Mat(); val gray = Mat(); val edges = Mat(); val lines = Mat(); val output = Mat()
        return try {
            Utils.bitmapToMat(source.bitmap, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.Canny(gray, edges, 50.0, 150.0)
            Imgproc.HoughLinesP(edges, lines, 1.0, Math.PI / 1800.0, 100, rgba.cols() * 0.5, 25.0)
            val angles = (0 until lines.rows()).mapNotNull { index ->
                val p = lines.get(index, 0) ?: return@mapNotNull null
                val angle = Math.toDegrees(atan2(p[3] - p[1], p[2] - p[0]))
                angle.takeIf { abs(it) < 8.0 && (p[1] + p[3]) / 2 in rgba.rows() * 0.2..rgba.rows() * 0.85 }
            }.sorted()
            if (angles.size < 4) return source
            val angle = (angles[(angles.size - 1) / 2] + angles[angles.size / 2]) / 2
            if (abs(angle) <= 0.15) return source
            val matrix = Imgproc.getRotationMatrix2D(Point(rgba.cols() / 2.0, rgba.rows() / 2.0), angle, 1.0)
            try {
                Imgproc.warpAffine(rgba, output, matrix, rgba.size(), Imgproc.INTER_CUBIC,
                    Core.BORDER_CONSTANT, Scalar(255.0, 255.0, 255.0, 255.0))
            } finally { matrix.release() }
            val bitmap = Bitmap.createBitmap(output.cols(), output.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(output, bitmap)
            source.copy(bitmap = bitmap, message = source.message + " / 표선 기울기 보정")
        } finally { rgba.release(); gray.release(); edges.release(); lines.release(); output.release() }
    }
}

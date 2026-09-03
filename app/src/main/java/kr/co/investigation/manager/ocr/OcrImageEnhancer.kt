package kr.co.investigation.manager.ocr

import android.graphics.Bitmap
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max

/**
 * OCR 인식용 보조 이미지 생성.
 * 원본/증거사진은 건드리지 않고 메모리 Bitmap만 생성한다.
 * 표 선이 글자와 붙어서 ML Kit가 문자를 누락하는 경우를 줄이기 위해
 * 가로/세로 표 선만 찾아 흰색으로 제거한 뒤 대비와 선명도를 보강한다.
 */
object OcrImageEnhancer {
    fun enhance(bitmap: Bitmap): Bitmap {
        if (!OpenCVLoader.initLocal()) return bitmap

        val rgba = Mat()
        val gray = Mat()
        val contrast = Mat()
        val binary = Mat()
        val horizontal = Mat()
        val vertical = Mat()
        val lineMask = Mat()
        val clean = Mat()
        val blur = Mat()
        val sharp = Mat()

        try {
            Utils.bitmapToMat(bitmap, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)

            val clahe = Imgproc.createCLAHE(2.4, Size(8.0, 8.0))
            clahe.apply(gray, contrast)

            Imgproc.adaptiveThreshold(
                contrast,
                binary,
                255.0,
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                Imgproc.THRESH_BINARY_INV,
                41,
                13.0
            )

            val horizontalKernel = Imgproc.getStructuringElement(
                Imgproc.MORPH_RECT,
                Size(max(32, bitmap.width / 25).toDouble(), 1.0)
            )
            val verticalKernel = Imgproc.getStructuringElement(
                Imgproc.MORPH_RECT,
                Size(1.0, max(32, bitmap.height / 70).toDouble())
            )

            Imgproc.morphologyEx(binary, horizontal, Imgproc.MORPH_OPEN, horizontalKernel)
            Imgproc.morphologyEx(binary, vertical, Imgproc.MORPH_OPEN, verticalKernel)
            Core.add(horizontal, vertical, lineMask)

            val dilateKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
            Imgproc.dilate(lineMask, lineMask, dilateKernel)

            contrast.copyTo(clean)
            clean.setTo(Scalar(255.0), lineMask)

            // 약한 언샤프 마스크. 너무 강한 이진화보다 한글 획을 더 잘 보존한다.
            Imgproc.GaussianBlur(clean, blur, Size(0.0, 0.0), 1.2)
            Core.addWeighted(clean, 1.45, blur, -0.45, 0.0, sharp)

            val bordered = Mat()
            Core.copyMakeBorder(
                sharp,
                bordered,
                12,
                12,
                12,
                12,
                Core.BORDER_CONSTANT,
                Scalar(255.0)
            )
            val out = Bitmap.createBitmap(bordered.cols(), bordered.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(bordered, out)
            bordered.release()
            horizontalKernel.release(); verticalKernel.release(); dilateKernel.release()
            return out
        } finally {
            rgba.release(); gray.release(); contrast.release(); binary.release()
            horizontal.release(); vertical.release(); lineMask.release(); clean.release(); blur.release(); sharp.release()
        }
    }
}

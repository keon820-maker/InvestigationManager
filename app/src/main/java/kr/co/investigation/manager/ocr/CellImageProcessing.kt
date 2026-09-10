package kr.co.investigation.manager.ocr

import android.graphics.Bitmap
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

/** Cell borders are already excluded. Never erase lines through the remaining characters. */
internal object CellImageProcessing {
    fun contrast(bitmap: Bitmap): Bitmap {
        if (!OpenCVLoader.initLocal()) return bitmap
        val rgba=Mat(); val gray=Mat(); val enhanced=Mat()
        val clahe=Imgproc.createCLAHE(1.5, Size(8.0,8.0))
        return try {
            Utils.bitmapToMat(bitmap,rgba)
            Imgproc.cvtColor(rgba,gray,Imgproc.COLOR_RGBA2GRAY)
            clahe.apply(gray,enhanced)
            Bitmap.createBitmap(enhanced.cols(),enhanced.rows(),Bitmap.Config.ARGB_8888).also { Utils.matToBitmap(enhanced,it) }
        } finally { rgba.release();gray.release();enhanced.release();clahe.collectGarbage() }
    }

    /** Conservative fast path for empty tenant cells. Any character-sized ink triggers OCR. */
    fun hasInk(bitmap: Bitmap, cell: GridFormLayout.Cell): Boolean {
        if (!OpenCVLoader.initLocal() || cell.width<20 || cell.height<20) return true
        val inset=6
        val crop=Bitmap.createBitmap(bitmap,cell.left+inset,cell.top+inset,cell.width-inset*2,cell.height-inset*2)
        val rgba=Mat();val gray=Mat();val binary=Mat();val labels=Mat();val stats=Mat();val centers=Mat()
        return try {
            Utils.bitmapToMat(crop,rgba)
            Imgproc.cvtColor(rgba,gray,Imgproc.COLOR_RGBA2GRAY)
            Imgproc.adaptiveThreshold(gray,binary,255.0,Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,Imgproc.THRESH_BINARY_INV,31,15.0)
            val count=Imgproc.connectedComponentsWithStats(binary,labels,stats,centers)
            (1 until count).any { i -> stats.get(i,Imgproc.CC_STAT_WIDTH)[0]>=2 &&
                stats.get(i,Imgproc.CC_STAT_HEIGHT)[0]>=4 && stats.get(i,Imgproc.CC_STAT_AREA)[0]>=8 }
        } finally { crop.recycle();rgba.release();gray.release();binary.release();labels.release();stats.release();centers.release() }
    }
}

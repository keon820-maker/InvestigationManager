package kr.co.investigation.manager.storage

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kr.co.investigation.manager.data.Attachment
import java.io.*
import java.security.MessageDigest

object OriginalFileStore {
    data class Saved(val attachment: Attachment)

    suspend fun copyOriginal(context:Context, source:Uri, caseId:Long, year:Int, type:String):Saved = withContext(Dispatchers.IO) {
        val dir=File(context.filesDir,"originals/$year/$caseId").apply{mkdirs()}
        val ext = context.contentResolver.getType(source)?.substringAfter('/')?.replace("jpeg","jpg") ?: "jpg"
        val file=File(dir,"${type.lowercase()}_${System.currentTimeMillis()}.$ext")
        try {
            val input = context.contentResolver.openInputStream(source) ?: error("원본 파일을 열 수 없습니다.")
            input.use { sourceInput ->
                FileOutputStream(file).buffered().use { output ->
                    sourceInput.copyTo(output, COPY_BUFFER_SIZE)
                }
            }
            Saved(buildAttachment(file,caseId,type,context.contentResolver.getType(source)?:"image/jpeg"))
        } catch (error: Throwable) {
            if (file.exists()) file.delete()
            throw error
        }
    }

    fun createCameraTarget(context:Context, year:Int, tempKey:String):File {
        val dir = File(context.filesDir, "originals/$year/pending")
        check(dir.isDirectory || dir.mkdirs()) { "사진 저장 폴더를 만들 수 없습니다." }
        return File.createTempFile("camera_${tempKey}_", ".jpg", dir)
    }

    suspend fun finalizeCamera(file:File,caseId:Long,type:String):Saved = withContext(Dispatchers.IO) {
        val yearDir = file.parentFile?.parentFile
            ?: error("Invalid camera target path: ${file.absolutePath}")
        val finalDir=File(yearDir,"$caseId").apply{mkdirs()}
        val final=File(finalDir,"${type.lowercase()}_${System.currentTimeMillis()}.jpg")
        if (!file.renameTo(final)) {
            try {
                file.inputStream().buffered().use { input ->
                    final.outputStream().buffered().use { output -> input.copyTo(output, COPY_BUFFER_SIZE) }
                }
            } catch (error: Throwable) {
                if (final.exists()) final.delete()
                throw error
            }
            file.delete()
        }
        Saved(buildAttachment(final,caseId,type,"image/jpeg"))
    }

    fun cloudDestination(
        context: Context,
        year: Int,
        caseId: Long,
        cloudId: String,
        originalName: String
    ): File {
        val dir = File(context.filesDir, "originals/$year/$caseId").apply { mkdirs() }
        val extension = originalName.substringAfterLast('.', "")
            .lowercase()
            .filter { it.isLetterOrDigit() }
            .take(10)
        val name = if (extension.isBlank()) "cloud_$cloudId" else "cloud_$cloudId.$extension"
        return File(dir, name)
    }

    private fun buildAttachment(file:File,caseId:Long,type:String,mime:String):Attachment {
        val opts=BitmapFactory.Options().apply{inJustDecodeBounds=true}
        BitmapFactory.decodeFile(file.absolutePath,opts)
        val exif=runCatching{ExifInterface(file)}.getOrNull()
        val dt=exif?.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: exif?.getAttribute(ExifInterface.TAG_DATETIME)
        return Attachment(
            caseId=caseId,
            type=type,
            originalName=file.name,
            localPath=file.absolutePath,
            mimeType=mime,
            byteSize=file.length(),
            width=opts.outWidth.takeIf{it>0},
            height=opts.outHeight.takeIf{it>0},
            capturedAt=dt,
            sha256=sha256(file)
        )
    }

    fun sha256(file:File):String {
        val md=MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val b=ByteArray(1024*1024)
            while(true){
                val n=input.read(b)
                if(n<0) break
                md.update(b,0,n)
            }
        }
        return md.digest().joinToString(""){"%02x".format(it)}
    }

    private const val COPY_BUFFER_SIZE = 256 * 1024
}

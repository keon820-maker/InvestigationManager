package kr.co.investigation.manager.storage

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kr.co.investigation.manager.data.Attachment
import java.io.*
import java.security.MessageDigest

object OriginalFileStore {
    data class Saved(val attachment: Attachment)

    fun copyOriginal(context:Context, source:Uri, caseId:Long, year:Int, type:String):Saved {
        val dir=File(context.filesDir,"originals/$year/$caseId").apply{mkdirs()}
        val ext = context.contentResolver.getType(source)?.substringAfter('/')?.replace("jpeg","jpg") ?: "jpg"
        val file=File(dir,"${type.lowercase()}_${System.currentTimeMillis()}.$ext")
        context.contentResolver.openInputStream(source)!!.use { input -> FileOutputStream(file).use { input.copyTo(it) } }
        return Saved(buildAttachment(file,caseId,type,context.contentResolver.getType(source)?:"image/jpeg"))
    }

    fun createCameraTarget(context:Context, year:Int, tempKey:String):File {
        return File(context.filesDir,"originals/$year/pending").apply{mkdirs()}.let { File(it,"camera_${tempKey}_${System.currentTimeMillis()}.jpg") }
    }

    fun finalizeCamera(file:File,caseId:Long,type:String):Saved {
        val yearDir = file.parentFile?.parentFile
            ?: error("Invalid camera target path: ${file.absolutePath}")
        val finalDir=File(yearDir,"$caseId").apply{mkdirs()}
        val final=File(finalDir,"${type.lowercase()}_${System.currentTimeMillis()}.jpg")
        file.copyTo(final, overwrite=true)
        file.delete()
        return Saved(buildAttachment(final,caseId,type,"image/jpeg"))
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
}

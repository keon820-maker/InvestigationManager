package kr.co.investigation.manager

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import kr.co.investigation.manager.storage.OriginalFileStore
import java.io.File

internal fun localDocumentImageIntent() = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
    type = "image/*"
    addCategory(Intent.CATEGORY_OPENABLE)
    putExtra(Intent.EXTRA_LOCAL_ONLY, true)
}

internal fun localGalleryIntent(context: Context): Intent {
    val gallery = Intent(Intent.ACTION_PICK).apply {
        setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*")
        setPackage("com.sec.android.gallery3d")
        putExtra(Intent.EXTRA_LOCAL_ONLY, true)
    }
    return if (gallery.resolveActivity(context.packageManager) != null) gallery else localDocumentImageIntent()
}

internal class DocumentCameraContract : ActivityResultContracts.TakePicture() {
    override fun createIntent(context: Context, input: Uri): Intent = super.createIntent(context, input).apply {
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        clipData = ClipData.newRawUri("document", input)
    }
}

internal data class DocumentPhotoActions(val choosePhoto: () -> Unit, val takePhoto: () -> Unit)

/** Only delegates to another camera app; our app does not access camera hardware. */
@Composable
internal fun rememberDocumentPhotoActions(
    year: Int,
    onPhoto: (Uri, File?) -> Unit,
    onError: (String) -> Unit
): DocumentPhotoActions {
    val context = LocalContext.current
    val currentPhoto by rememberUpdatedState(onPhoto)
    val currentError by rememberUpdatedState(onError)
    var pendingPath by rememberSaveable { mutableStateOf<String?>(null) }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                val readFlag = result.data!!.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
                runCatching { context.contentResolver.takePersistableUriPermission(uri, readFlag) }
                currentPhoto(uri, null)
            }
        }
    }
    val camera = rememberLauncherForActivityResult(DocumentCameraContract()) { success ->
        val file = pendingPath?.let(::File)
        pendingPath = null
        if (success && file != null && file.isFile && file.length() > 0) {
            try {
                currentPhoto(FileProvider.getUriForFile(context, "${context.packageName}.files", file), file)
            } catch (_: Exception) {
                file.delete()
                currentError("촬영한 사진을 열 수 없습니다. 다시 촬영해주세요.")
            }
        } else {
            file?.delete()
            if (success) currentError("촬영한 사진이 저장되지 않았습니다. 다시 촬영해주세요.")
        }
    }
    return DocumentPhotoActions(
        choosePhoto = {
            try {
                gallery.launch(localGalleryIntent(context))
            } catch (_: Exception) {
                try { gallery.launch(localDocumentImageIntent()) }
                catch (_: Exception) { currentError("기기에서 사진 선택 화면을 열 수 없습니다.") }
            }
        },
        takePhoto = {
            if (pendingPath == null) {
                try {
                    val file = OriginalFileStore.createCameraTarget(context, year, "capture")
                    pendingPath = file.absolutePath
                    camera.launch(FileProvider.getUriForFile(context, "${context.packageName}.files", file))
                } catch (_: Exception) {
                    pendingPath?.let(::File)?.delete()
                    pendingPath = null
                    currentError("카메라를 실행할 수 없습니다. 카메라 앱 사용 가능 여부를 확인해주세요.")
                }
            }
        }
    )
}

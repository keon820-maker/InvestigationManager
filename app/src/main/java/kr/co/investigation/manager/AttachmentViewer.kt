@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package kr.co.investigation.manager

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kr.co.investigation.manager.data.Attachment
import java.io.File
import kotlin.math.max

private data class PreviewResult(val bitmap: Bitmap? = null, val error: String = "")

@Composable
fun AttachmentViewerScreen(att: Attachment, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var scale by remember(att.id) { mutableFloatStateOf(1f) }
    var offset by remember(att.id) { mutableStateOf(Offset.Zero) }
    var openError by remember(att.id) { mutableStateOf("") }
    val preview by produceState(initialValue = PreviewResult(), att.localPath) {
        value = withContext(Dispatchers.IO) {
            runCatching { PreviewResult(bitmap = loadPreviewBitmap(att.localPath)) }
                .getOrElse { PreviewResult(error = it.message ?: "원본 미리보기를 열 수 없습니다.") }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(attachmentTitle(att)) },
                navigationIcon = { TextButton(onClick = onBack) { Text("뒤로") } },
                actions = {
                    TextButton(onClick = { scale = 1f; offset = Offset.Zero }) { Text("맞춤") }
                    TextButton(onClick = {
                        openError = ""
                        runCatching {
                            val file = File(att.localPath)
                            require(file.exists()) { "원본 파일이 없습니다." }
                            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, att.mimeType.ifBlank { "image/*" })
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            require(intent.resolveActivity(ctx.packageManager) != null) { "이 파일을 열 수 있는 앱이 없습니다." }
                            ctx.startActivity(intent)
                        }.onFailure { openError = it.message ?: "원본 열기에 실패했습니다." }
                    }) { Text("외부 앱으로 열기") }
                }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Text(
                "${att.originalName}  ·  ${att.width ?: "?"}×${att.height ?: "?"}  ·  ${att.byteSize / 1024} KB\nSHA-256 ${att.sha256}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            )
            if (openError.isNotBlank()) {
                Text(openError, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            }
            HorizontalDivider()
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0xFF161616))
                    .pointerInput(att.id) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val nextScale = (scale * zoom).coerceIn(1f, 8f)
                            scale = nextScale
                            offset = if (nextScale <= 1.01f) Offset.Zero else offset + pan
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                when {
                    preview.error.isNotBlank() -> Text(preview.error, color = Color.White, modifier = Modifier.padding(20.dp))
                    preview.bitmap == null -> CircularProgressIndicator()
                    else -> {
                        androidx.compose.foundation.Image(
                            bitmap = preview.bitmap!!.asImageBitmap(),
                            contentDescription = att.originalName,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer(
                                    scaleX = scale,
                                    scaleY = scale,
                                    translationX = offset.x,
                                    translationY = offset.y
                                )
                        )
                        Surface(
                            modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
                            color = Color.Black.copy(alpha = .62f),
                            contentColor = Color.White,
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Text(
                                "두 손가락으로 확대·축소 / 드래그 이동  ${(scale * 100).toInt()}%",
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun attachmentTitle(att: Attachment): String = when (att.type) {
    "ORIGINAL_REQUEST" -> "원본 조사의뢰서"
    "CONFIRMATION" -> "조사확인서 원본"
    else -> "첨부 원본"
}

private fun loadPreviewBitmap(path: String, maxSide: Int = 1600): Bitmap {
    val file = File(path)
    require(file.exists()) { "원본 파일이 없습니다." }

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "지원하지 않는 이미지 형식입니다." }

    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2

    val decoded = BitmapFactory.decodeFile(
        path,
        BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
    ) ?: error("이미지 미리보기를 만들 수 없습니다.")

    val rotation = runCatching { ExifInterface(path).rotationDegrees }.getOrDefault(0)
    if (rotation == 0) return decoded

    return try {
        Bitmap.createBitmap(
            decoded,
            0,
            0,
            decoded.width,
            decoded.height,
            Matrix().apply { postRotate(rotation.toFloat()) },
            true
        ).also { if (it !== decoded) decoded.recycle() }
    } catch (t: Throwable) {
        if (!decoded.isRecycled) decoded.recycle()
        throw t
    }
}

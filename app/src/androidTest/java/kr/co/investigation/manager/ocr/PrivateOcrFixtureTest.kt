package kr.co.investigation.manager.ocr

import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Optional local harness. No real fixtures or expected values are committed or sent to CI. */
class PrivateOcrFixtureTest {
    @Test fun localFixtures() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val enabled = InstrumentationRegistry.getArguments().getString("privateFixtures") == "true"
        assumeTrue(enabled)
        assertEquals("Private fixture runner requires an APK with INTERNET removed", PackageManager.PERMISSION_DENIED,
            context.checkSelfPermission(android.Manifest.permission.INTERNET))
        val input = File(context.filesDir, "private-ocr-input")
        val output = File(context.filesDir, "private-ocr-output").apply { mkdirs() }
        val files = input.listFiles { file -> file.extension.lowercase() in setOf("jpg", "jpeg", "png") }.orEmpty().sortedBy { it.name }
        require(files.isNotEmpty()) { "No local fixtures supplied" }
        for ((index, file) in files.withIndex()) {
            val start = System.currentTimeMillis()
            File(output, "progress.txt").writeText("$index/${files.size}; normalizing sample ${index + 1}")
            val gridOnly = InstrumentationRegistry.getArguments().getString("gridOnly") == "true"
            val result = if (gridOnly) {
                val normalized = DocumentNormalizer.normalize(context, Uri.fromFile(file))
                try {
                    File(output, "progress.txt").writeText("$index/${files.size}; reading cells in sample ${index + 1}")
                    GridFormOcr.recognize(normalized) ?: OcrService.OcrResult("",
                        kr.co.investigation.manager.data.InvestigationCase(year = 2026), false, "Grid not verified")
                } finally { normalized.bitmap.recycle() }
            } else OcrService.recognizeCase(context, Uri.fromFile(file))
            // App-private output only. Never put raw values in assertions, logcat or CI reports.
            File(output, "sample-${index + 1}.json").writeText(Gson().toJson(result))
            File(output, "progress.txt").writeText("${index + 1}/${files.size}; ${System.currentTimeMillis() - start}ms")
        }
    }
}

package kr.co.investigation.manager

import android.net.Uri
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.Job
import kr.co.investigation.manager.data.InvestigationCase
import java.io.File
import java.time.LocalDate

/** Kept by AppViewModel so rotation does not discard a draft or interrupt recognition. */
class OcrRegistrationDraft {
    val raw = mutableStateOf("")
    val parsed = mutableStateOf(InvestigationCase(year = LocalDate.now().year))
    val busy = mutableStateOf(false)
    val saving = mutableStateOf(false)
    val saved = mutableStateOf(false)
    val source = mutableStateOf<Uri?>(null)
    val cameraFile = mutableStateOf<File?>(null)
    val cameraSource = mutableStateOf(false)
    val preprocess = mutableStateOf("")
    var job: Job? = null
    var generation = 0

    fun reset() {
        generation++
        job?.cancel()
        job = null
        cameraFile.value?.delete()
        raw.value = ""
        parsed.value = InvestigationCase(year = LocalDate.now().year)
        busy.value = false
        saving.value = false
        saved.value = false
        source.value = null
        cameraFile.value = null
        cameraSource.value = false
        preprocess.value = ""
    }
}

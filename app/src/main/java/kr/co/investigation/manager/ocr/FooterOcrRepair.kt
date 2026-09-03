package kr.co.investigation.manager.ocr

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kr.co.investigation.manager.data.InvestigationCase
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 하단 영업점/조사의뢰자 영역 전용 보정.
 * v0.11에서 중앙 표 기준 정렬은 잘 되었지만 하단 고정 crop이 한 행씩 아래로 밀려
 * 조사의뢰자에 "전화번호"가 들어가는 문제가 있어 실제 정규화 이미지 좌표로 다시 읽는다.
 */
object FooterOcrRepair {
    private const val W = 2480.0
    private const val H = 3508.0

    private data class Box(val l: Int, val t: Int, val r: Int, val b: Int)

    // 실제 정규화된 조사의뢰서(warp_anchor.jpg)에서 값 부분만 다시 측정한 좌표.
    private val BRANCH = Box(1380, 2810, 1830, 2905)
    private val REQUESTER = Box(1380, 2915, 1720, 3010)

    suspend fun repair(context: Context, uri: Uri, base: OcrService.OcrResult): OcrService.OcrResult {
        val needsFooter = !validBranch(base.parsed.branch) || !validRequester(base.parsed.requester)
        val cleanedNotes = cleanNotes(base.parsed.requestNotes)
        if (!needsFooter) {
            return if (cleanedNotes == base.parsed.requestNotes) base
            else base.copy(parsed = base.parsed.copy(requestNotes = cleanedNotes))
        }

        val normalized = runCatching { DocumentNormalizer.normalize(context, uri) }.getOrNull()
            ?: return base.copy(parsed = base.parsed.copy(requestNotes = cleanedNotes))
        if (!normalized.documentDetected || normalized.bitmap.width < 2000 || normalized.bitmap.height < 2800) {
            return base.copy(parsed = base.parsed.copy(requestNotes = cleanedNotes))
        }

        val client = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        return try {
            val branchRaw = readBox(client, normalized.bitmap, BRANCH)
            val requesterRaw = readBox(client, normalized.bitmap, REQUESTER)

            val branch = normalizeBranch(branchRaw).takeIf(::validBranch).orEmpty()
            val requester = normalizeRequester(requesterRaw).takeIf(::validRequester).orEmpty()

            val fixed = base.parsed.copy(
                branch = if (branch.isNotBlank()) branch else base.parsed.branch.takeIf(::validBranch).orEmpty(),
                requester = if (requester.isNotBlank()) requester else base.parsed.requester.takeIf(::validRequester).orEmpty(),
                requestNotes = cleanedNotes
            )

            base.copy(
                parsed = fixed,
                rawText = base.rawText + buildString {
                    append("\n\n--- 하단 필드 재검증 v0.12 ---\n")
                    append("영업점 crop : ").append(branchRaw.replace('\n', ' ')).append('\n')
                    append("조사의뢰자 crop : ").append(requesterRaw.replace('\n', ' ')).append('\n')
                    append("영업점 확정 : ").append(fixed.branch).append('\n')
                    append("조사의뢰자 확정 : ").append(fixed.requester).append('\n')
                },
                preprocessMessage = base.preprocessMessage + " / 하단 영업점·의뢰자 재검증"
            )
        } finally {
            client.close()
        }
    }

    private suspend fun readBox(
        client: com.google.mlkit.vision.text.TextRecognizer,
        source: Bitmap,
        box: Box
    ): String {
        val sx = source.width / W
        val sy = source.height / H
        val l = (box.l * sx).toInt().coerceIn(0, source.width - 2)
        val t = (box.t * sy).toInt().coerceIn(0, source.height - 2)
        val r = (box.r * sx).toInt().coerceIn(l + 1, source.width)
        val b = (box.b * sy).toInt().coerceIn(t + 1, source.height)
        val crop = Bitmap.createBitmap(source, l, t, r - l, b - t)
        return try {
            val text = suspendCancellableCoroutine<com.google.mlkit.vision.text.Text> { c ->
                client.process(InputImage.fromBitmap(crop, 0))
                    .addOnSuccessListener { if (c.isActive) c.resume(it) }
                    .addOnFailureListener { if (c.isActive) c.resumeWithException(it) }
            }
            text.text.lines().joinToString(" ") { it.trim() }.replace(Regex("\\s+"), " ").trim()
        } finally {
            crop.recycle()
        }
    }

    private fun normalizeBranch(raw: String): String {
        var s = raw.replace(Regex("[▷>|:：]"), " ").replace(Regex("\\s+"), " ").trim()
        s = s.replace(Regex(".*?영업점\\s*"), "")
        val m = Regex("[가-힣0-9]{2,20}지점").find(s.replace(" ", ""))
        return m?.value.orEmpty()
    }

    private fun normalizeRequester(raw: String): String {
        val s = raw.replace(Regex("[▷>|:：]"), " ").replace(Regex("\\s+"), " ").trim()
        val candidates = Regex("[가-힣]{2,5}").findAll(s).map { it.value }.toList()
        return candidates.firstOrNull { validRequester(it) }.orEmpty()
    }

    private fun validBranch(value: String): Boolean {
        val s = value.replace(" ", "")
        return s.length in 4..30 && s.endsWith("지점") && !s.contains("조사의뢰자") && !s.contains("전화번호")
    }

    private fun validRequester(value: String): Boolean {
        val s = value.trim()
        if (!Regex("[가-힣]{2,5}").matches(s)) return false
        val bad = setOf("전화번호", "조사의뢰자", "농협영업점", "신청인", "연락처", "팩스", "채무자명", "완료요청일")
        return s !in bad && !s.contains("전화") && !s.contains("의뢰") && !s.contains("영업점")
    }

    private fun cleanNotes(value: String): String = value
        .replace(Regex("(^|\\s)증금\\s*[:：]"), "$1보증금:")
        .replace(Regex("보증금\\s*[:：]\\s*0I\\b", RegexOption.IGNORE_CASE), "보증금:0")
        .replace(Regex("월임차료\\s*[:：]\\s*[oO]\\b"), "월임차료:0")
        .trim()
}

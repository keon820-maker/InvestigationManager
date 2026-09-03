package kr.co.investigation.manager.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max

/**
 * 하단 영업점/조사의뢰자 영역 전용 보정.
 *
 * v0.12의 고정 Y crop은 촬영 각도/정렬 방식에 따라 한 행씩 밀릴 수 있었다.
 * v0.13부터는 하단 전체를 먼저 OCR한 뒤 '농협영업점', '조사의뢰자' 라벨의 실제
 * boundingBox Y좌표를 찾아 그 행만 다시 확대 OCR한다. 따라서 하단 위치가 조금 이동해도
 * 영업점과 조사의뢰자 행을 서로 바꾸어 읽지 않는다.
 */
object FooterOcrRepair {
    private data class FooterLine(val text: String, val box: Rect)

    suspend fun repair(context: Context, uri: Uri, base: OcrService.OcrResult): OcrService.OcrResult {
        val cleanedNotes = cleanNotes(base.parsed.requestNotes)
        val needsFooter = !validBranch(base.parsed.branch) || !validRequester(base.parsed.requester)
        if (!needsFooter) {
            return if (cleanedNotes == base.parsed.requestNotes) base
            else base.copy(parsed = base.parsed.copy(requestNotes = cleanedNotes))
        }

        val normalized = runCatching { DocumentNormalizer.normalize(context, uri) }.getOrNull()
            ?: return base.copy(parsed = base.parsed.copy(requestNotes = cleanedNotes))
        if (!normalized.documentDetected || normalized.bitmap.width < 1800 || normalized.bitmap.height < 2500) {
            return base.copy(parsed = base.parsed.copy(requestNotes = cleanedNotes))
        }

        val client = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        return try {
            val source = normalized.bitmap
            val footerTop = (source.height * 0.68f).toInt().coerceIn(0, source.height - 2)
            val footerBottom = (source.height * 0.97f).toInt().coerceIn(footerTop + 1, source.height)
            val footerCrop = Bitmap.createBitmap(source, 0, footerTop, source.width, footerBottom - footerTop)

            val footerText = try {
                recognize(client, footerCrop)
            } finally {
                footerCrop.recycle()
            }

            val lines = footerText.textBlocks
                .flatMap { it.lines }
                .mapNotNull { line -> line.boundingBox?.let { FooterLine(clean(line.text), it) } }
                .filter { it.text.isNotBlank() }

            val branchLine = findLine(lines, "농협영업점", "농협 영업점", "영업점")
            val requesterLine = findLine(lines, "조사의뢰자", "조사 의뢰자")

            val branchAttempts = mutableListOf<String>()
            val requesterAttempts = mutableListOf<String>()

            branchLine?.let { line ->
                branchAttempts += line.text
                branchAttempts += readDynamicRow(
                    client = client,
                    source = source,
                    absoluteCenterY = footerTop + line.box.centerY(),
                    lineHeight = line.box.height(),
                    xStartRatio = 0.50f,
                    xEndRatio = 0.86f
                )
            }
            requesterLine?.let { line ->
                requesterAttempts += line.text
                requesterAttempts += readDynamicRow(
                    client = client,
                    source = source,
                    absoluteCenterY = footerTop + line.box.centerY(),
                    lineHeight = line.box.height(),
                    xStartRatio = 0.50f,
                    xEndRatio = 0.76f
                )
            }

            // 기존 결과도 마지막 후보로만 사용한다. 잘못된 '전화번호' 같은 값은 validator에서 탈락한다.
            branchAttempts += base.parsed.branch
            requesterAttempts += base.parsed.requester

            val branch = branchAttempts
                .asSequence()
                .map(::normalizeBranch)
                .firstOrNull(::validBranch)
                .orEmpty()

            val requester = requesterAttempts
                .asSequence()
                .map(::normalizeRequester)
                .firstOrNull(::validRequester)
                .orEmpty()

            val fixed = base.parsed.copy(
                branch = branch,
                requester = requester,
                requestNotes = cleanedNotes
            )

            base.copy(
                parsed = fixed,
                rawText = base.rawText + buildString {
                    append("\n\n--- 하단 동적 행 재검증 v0.13 ---\n")
                    append("영업점 라벨행 : ").append(branchLine?.text.orEmpty()).append('\n')
                    append("영업점 재OCR : ").append(branchAttempts.drop(1).firstOrNull().orEmpty()).append('\n')
                    append("조사의뢰자 라벨행 : ").append(requesterLine?.text.orEmpty()).append('\n')
                    append("조사의뢰자 재OCR : ").append(requesterAttempts.drop(1).firstOrNull().orEmpty()).append('\n')
                    append("영업점 확정 : ").append(fixed.branch).append('\n')
                    append("조사의뢰자 확정 : ").append(fixed.requester).append('\n')
                },
                preprocessMessage = base.preprocessMessage + " / 하단 라벨 위치기반 재검증 v0.13"
            )
        } finally {
            client.close()
        }
    }

    private fun findLine(lines: List<FooterLine>, vararg labels: String): FooterLine? {
        val exact = lines.filter { line ->
            labels.any { compact(line.text).contains(compact(it)) }
        }
        return exact.minByOrNull { it.box.top }
    }

    /** 라벨이 실제로 검출된 Y행을 기준으로 값 영역만 확대해 두 번 OCR한다. */
    private suspend fun readDynamicRow(
        client: TextRecognizer,
        source: Bitmap,
        absoluteCenterY: Int,
        lineHeight: Int,
        xStartRatio: Float,
        xEndRatio: Float
    ): String {
        val half = max((lineHeight * 1.35f).toInt(), (source.height * 0.014f).toInt())
        val top = (absoluteCenterY - half).coerceIn(0, source.height - 2)
        val bottom = (absoluteCenterY + half).coerceIn(top + 1, source.height)
        val left = (source.width * xStartRatio).toInt().coerceIn(0, source.width - 2)
        val right = (source.width * xEndRatio).toInt().coerceIn(left + 1, source.width)
        val crop = Bitmap.createBitmap(source, left, top, right - left, bottom - top)
        return try {
            val raw = clean(recognize(client, crop).text)
            val enhanced = OcrImageEnhancer.enhance(crop)
            val enhancedText = try {
                if (enhanced === crop) "" else clean(recognize(client, enhanced).text)
            } finally {
                if (enhanced !== crop && !enhanced.isRecycled) enhanced.recycle()
            }
            listOf(raw, enhancedText).filter { it.isNotBlank() }.joinToString(" || ")
        } finally {
            if (!crop.isRecycled) crop.recycle()
        }
    }

    private suspend fun recognize(client: TextRecognizer, bitmap: Bitmap): Text =
        suspendCancellableCoroutine { c ->
            client.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { if (c.isActive) c.resume(it) }
                .addOnFailureListener { if (c.isActive) c.resumeWithException(it) }
        }

    private fun normalizeBranch(raw: String): String {
        var s = clean(raw)
            .replace(Regex("[▷>|:：]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        // 라벨이 포함되어 있으면 라벨 뒤의 값만 사용한다.
        s = s.replace(Regex(".*?(농\\s*협\\s*)?영\\s*업\\s*점\\s*", RegexOption.IGNORE_CASE), "")
        val compacted = s.replace(" ", "")
        return Regex("[가-힣0-9]{2,24}지점").find(compacted)?.value.orEmpty()
    }

    private fun normalizeRequester(raw: String): String {
        var s = clean(raw)
            .replace(Regex("[▷>|:：]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        s = s.replace(Regex(".*?조\\s*사\\s*의\\s*뢰\\s*자\\s*", RegexOption.IGNORE_CASE), "")
        val candidates = Regex("[가-힣]{2,5}").findAll(s).map { it.value }.toList()
        return candidates.firstOrNull(::validRequester).orEmpty()
    }

    private fun validBranch(value: String): Boolean {
        val s = value.replace(" ", "")
        return s.length in 4..30 && s.endsWith("지점") &&
            !s.contains("조사의뢰자") && !s.contains("전화번호") && !s.contains("신청인")
    }

    private fun validRequester(value: String): Boolean {
        val s = value.trim()
        if (!Regex("[가-힣]{2,5}").matches(s)) return false
        val bad = setOf(
            "전화번호", "조사의뢰자", "농협영업점", "신청인", "연락처", "팩스",
            "채무자명", "완료요청일", "전화번", "영업점"
        )
        return s !in bad && !s.contains("전화") && !s.contains("의뢰") &&
            !s.contains("영업점") && !s.contains("신청")
    }

    private fun cleanNotes(value: String): String = value
        .replace(Regex("(^|\\s)증금\\s*[:：]"), "$1보증금:")
        .replace(Regex("보증금\\s*[:：]\\s*0I\\b", RegexOption.IGNORE_CASE), "보증금:0")
        .replace(Regex("월임차료\\s*[:：]\\s*[oO]\\b"), "월임차료:0")
        .trim()

    private fun clean(value: String): String = value.lines()
        .map { it.replace(Regex("[\\t ]+"), " ").trim() }
        .filter { it.isNotBlank() }
        .joinToString(" ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun compact(value: String): String = value.replace(Regex("[^가-힣A-Za-z0-9]"), "")
}

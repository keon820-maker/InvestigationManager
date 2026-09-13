package kr.co.investigation.manager.ocr

import kotlin.math.abs

/** Uses printed Korean labels and their reading direction, never the photo's aspect ratio. */
internal object DocumentOrientationScore {
    data class Line(val text: String, val centerY: Float, val angle: Float = 0f)
    data class Candidate(val clockwiseDegrees: Int, val anchors: Int, val score: Int, val titleAtTop: Boolean)

    private val headings = listOf(
        "조사의뢰서", "관리번호", "의뢰일", "채무자", "완료요청일", "조사구분",
        "대출종류", "물건종류", "물건소재지", "소유자주소", "기타요청사항", "조사의뢰자", "영업점"
    )

    fun score(clockwiseDegrees: Int, lines: List<Line>): Candidate {
        val readable = lines.filter { it.angle.isFinite() && abs(it.angle) <= 35f }
            .map { it.copy(text = it.text.replace(Regex("[^가-힣]"), "")) }
        val matches = headings.mapNotNull { heading ->
            readable.firstOrNull { heading in it.text }?.let { heading to it }
        }
        val title = matches.firstOrNull { it.first == "조사의뢰서" }?.second
        val titleAtTop = title != null && title.centerY <= .40f
        val score = matches.sumOf { (heading, line) ->
            val weight = if (heading == "조사의뢰서") 10 else 4
            val location = when (heading) {
                "조사의뢰서" -> if (line.centerY <= .40f) 5 else -10
                "관리번호", "의뢰일", "채무자", "완료요청일" -> if (line.centerY < .60f) 1 else -2
                "기타요청사항", "조사의뢰자", "영업점" -> if (line.centerY > .45f) 1 else -2
                else -> 0
            }
            weight + location
        }
        return Candidate(clockwiseDegrees, matches.size, score, titleAtTop)
    }

    fun isClearlyUpright(candidate: Candidate): Boolean =
        candidate.clockwiseDegrees == 0 && candidate.anchors >= 7 && candidate.titleAtTop && candidate.score >= 35

    /** Ambiguous/blank images retain their EXIF-corrected orientation. */
    fun choose(candidates: List<Candidate>): Candidate? {
        val ranked = candidates.sortedByDescending { it.score }
        val best = ranked.firstOrNull() ?: return null
        if (best.anchors < 3 || best.score < 14) return null
        val second = ranked.getOrNull(1)
        if (second != null && best.score - second.score < 6) return null
        return best
    }
}

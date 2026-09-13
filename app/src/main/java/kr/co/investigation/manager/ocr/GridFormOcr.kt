package kr.co.investigation.manager.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlinx.coroutines.tasks.await
import kr.co.investigation.manager.data.InvestigationCase
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** Verified printed grid is authoritative. Legacy repairs never overwrite this result. */
internal object GridFormOcr {
    suspend fun recognize(source: DocumentNormalizer.Result, batchCells: Boolean = true): OcrService.OcrResult? {
        val bitmap = source.bitmap
        val cells = TableCellDetector.detect(bitmap).map { GridFormLayout.Cell(it.left, it.top, it.right, it.bottom) }
        val layout = GridFormLayout.resolve(cells) ?: return null
        val client = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        try {
            // Topology alone is insufficient: confirm independent labels before assigning roles.
            val labelReads = if (batchCells) CellBatchReader.read(client, bitmap,
                layout.labelCells.map { CellBatchReader.Input(it.key, it.value) }) else emptyMap()
            val expectedLabels = mapOf("debtor" to "채무자", "property" to "소재지", "tenant" to "차인")
            val labels = layout.labelCells.mapValues { (key, cell) ->
                val candidate = labelReads[key].orEmpty().replace(Regex("\\s+"), "")
                if (candidate.contains(expectedLabels.getValue(key))) candidate
                else read(client, bitmap, cell).replace(Regex("\\s+"), "")
            }
            if (labels["debtor"]?.contains("채무자") != true ||
                labels["property"]?.contains("소재지") != true ||
                labels["tenant"]?.contains("차인") != true) return null

            val review = linkedSetOf<String>()
            val raw = linkedMapOf<String, String>()
            val valueCells = layout.fields.toMutableMap().apply {
                layout.tenants.forEachIndexed { i, pair ->
                    put("tenant${i + 1}.name", pair.first); put("tenant${i + 1}.phone", pair.second)
                }
                put("requestNotes", layout.notes)
            }
            val emptyTenants = valueCells.filter { (key, cell) -> key.startsWith("tenant") && !CellImageProcessing.hasInk(bitmap, cell) }.keys
            // Korean identities lose recognition context in a mixed panel on some photos.
            // Keep their original isolated reads; never trade identity accuracy for batching.
            val isolatedKeys = valueCells.keys.filter { it == "debtorName" || it == "ownerIdentity" || it.endsWith(".name") }.toSet()
            val inputs = valueCells.filterKeys { it !in emptyTenants && it !in isolatedKeys }.map { CellBatchReader.Input(it.key, it.value) }
            val originals = if (batchCells) CellBatchReader.read(client, bitmap, inputs) else emptyMap()
            val contrasts = if (batchCells) CellBatchReader.read(client, bitmap, inputs, enhanced = true) else emptyMap()
            suspend fun value(key: String, cell: GridFormLayout.Cell, normalize: (String) -> String): String {
                if (key in emptyTenants) {
                    raw[key] = ""
                    return ""
                }
                val batched = batchCells && key !in isolatedKeys
                var first = if (batched) originals[key].orEmpty() else read(client, bitmap, cell)
                var second = if (batched) contrasts[key].orEmpty() else read(client, bitmap, cell, enhanced = true)
                // A rejected/missing batch line can only be retried in its own source cell.
                if (batched && first.isBlank() && second.isBlank() && CellImageProcessing.hasInk(bitmap, cell)) {
                    first = read(client, bitmap, cell)
                    second = read(client, bitmap, cell, enhanced = true)
                    if (first.isBlank() && second.isBlank()) review += key
                }
                raw[key] = first
                val choice = GridCellValues.choose(first, second, normalize)
                if (choice.review || (first.isNotBlank() && choice.value.isBlank())) {
                    review += key
                    raw["$key.retry"] = second
                }
                // For free text retain the original cell as a reviewable candidate; do not
                // discard an entire address or note because one enhancement changes a glyph.
                val freeText = key in setOf("investigationType", "propertyType", "propertyAddress", "ownerAddress", "requestNotes")
                return if (freeText && choice.value.isBlank()) normalize(first) else choice.value
            }
            suspend fun field(key: String, normalize: (String) -> String = GridCellValues::singleLine): String =
                value(key, layout.fields.getValue(key), normalize)

            val debtor = field("debtorName", GridCellValues::identity)
            if (debtor.isNotBlank() && !Regex("\\(\\d{6}").containsMatchIn(debtor)) review += "debtorName"
            val phone = field("phone", GridCellValues::phone)
            val mobile = field("mobile", GridCellValues::phone)
            val due = field("dueDate", GridCellValues::date)
            val investigation = field("investigationType")
            val loan = field("loanType", OcrFieldNormalizer::loanType)
            val propertyType = field("propertyType")
            val propertyAddress = field("propertyAddress", GridCellValues::address)
            val ownerIdentity = field("ownerIdentity", GridCellValues::identity)
            if (ownerIdentity.isNotBlank() && !Regex("\\(\\d{6}").containsMatchIn(ownerIdentity)) review += "ownerIdentity"
            // The model has one owner contact field; retain the first printed number, flag multiple.
            val ownerPhone = field("ownerPhone") { GridCellValues.phones(it).joinToString(" / ") }
            if (ownerPhone.contains(" / ")) review += "ownerPhone"
            val ownerAddress = field("ownerAddress", GridCellValues::address)

            val tenants = JSONArray()
            for ((index, pair) in layout.tenants.withIndex()) {
                val name = value("tenant${index + 1}.name", pair.first, GridCellValues::name)
                val number = value("tenant${index + 1}.phone", pair.second, GridCellValues::phone)
                if (name.isNotBlank()) tenants.put(JSONObject().put("name", name).put("phone", number))
                else if (number.isNotBlank()) review += "tenant${index + 1}.name"
            }
            val notes = value("requestNotes", layout.notes, GridCellValues::text)
            val target = layout.fields.getValue("debtorName")
            // The investigator row sits between management number and target table. Exclude it
            // from the input, not merely from the displayed result.
            val headerBottom = (target.top - target.height * 3.2).toInt().coerceIn(1, bitmap.height)
            val header = read(client, bitmap, GridFormLayout.Cell(0, 0, bitmap.width, headerBottom), inset = false)
            val requestDate = GridCellValues.date(header)
            val management = GridCellValues.management(header)
            val footer = read(client, bitmap, GridFormLayout.Cell(0, layout.notes.bottom, bitmap.width, bitmap.height), inset = false)
            fun footerValue(label: String): String {
                val pattern = label.map { Regex.escape(it.toString()) }.joinToString("\\s*")
                return Regex("$pattern\\s*[:：]?\\s*([^\\n]+)").find(footer)?.groupValues?.get(1)?.trim().orEmpty()
            }
            val footerPhones = footerValue("전화번호")
            val beforeFax = footerPhones.split(Regex("팩\\s*스|(?i)fax"), limit = 2)[0]
            val branchPhone = GridCellValues.phones(beforeFax).firstOrNull().orEmpty()
            val branchFax = GridCellValues.phones(footerValue("팩스")).firstOrNull().orEmpty()
            val parsed = InvestigationCase(
                year = requestDate.take(4).toIntOrNull() ?: LocalDate.now().year,
                managementNo = management, requestDate = requestDate,
                debtorName = debtor, phone = phone, mobile = mobile, dueDate = due,
                investigationType = investigation, loanType = loan, propertyType = propertyType,
                propertyAddress = propertyAddress, ownerName = ownerIdentity.substringBefore('('),
                ownerResidentNo = ownerIdentity.substringAfter('(', "").substringBefore(')'),
                ownerPhone = ownerPhone.substringBefore(" / "), ownerAddress = ownerAddress,
                tenantsJson = tenants.toString(), requestNotes = notes,
                branch = footerValue("농협영업점"), requester = footerValue("조사의뢰자"),
                branchPhone = branchPhone, branchFax = branchFax
            )
            val required = mapOf("managementNo" to management, "requestDate" to requestDate, "debtorName" to debtor,
                "dueDate" to due, "loanType" to loan, "propertyAddress" to propertyAddress, "ownerIdentity" to ownerIdentity,
                "ownerAddress" to ownerAddress, "branch" to parsed.branch)
            required.filterValues(String::isBlank).keys.forEach(review::add)
            return OcrService.OcrResult(
                sourceText = (listOf(header) + raw.filterKeys { !it.endsWith(".retry") }.values + footer).joinToString("\n"),
                rawText = buildString {
                    append("--- 실제 표 경계 OCR ---\n조사담당자 : [OCR 제외]\n")
                    raw.forEach { (key, text) -> append(key).append(" : ").append(text).append('\n') }
                    append("검토 항목 : ").append(review.joinToString())
                },
                parsed = parsed, normalized = true,
                preprocessMessage = source.message + " / 실제 칸 경계 확인 / 빈칸 유지" +
                    if (review.isEmpty()) " / 원본 대조 후 저장하세요" else " / 확인 필요: " + review.joinToString { displayName(it) }
            )
        } finally { client.close() }
    }

    private fun displayName(key: String): String = mapOf(
        "managementNo" to "관리번호", "requestDate" to "의뢰일", "debtorName" to "채무자·생년월일",
        "phone" to "채무자 전화", "mobile" to "채무자 휴대폰", "dueDate" to "완료요청일",
        "investigationType" to "조사구분", "loanType" to "대출종류", "propertyType" to "물건종류",
        "propertyAddress" to "물건소재지", "ownerIdentity" to "소유자", "ownerPhone" to "소유자 연락처",
        "ownerAddress" to "소유자 주소", "requestNotes" to "기타요청사항", "branch" to "영업점"
    )[key] ?: key.replace(Regex("tenant(\\d+)\\.name"), "임차인$1 이름").replace(Regex("tenant(\\d+)\\.phone"), "임차인$1 전화")

    private suspend fun read(client: TextRecognizer, source: Bitmap, cell: GridFormLayout.Cell,
                             enhanced: Boolean = false, inset: Boolean = true): String {
        // Small fixed inset preserves text printed close to a border in narrow/wrapped cells.
        val margin = if (inset) (source.width / 1000).coerceIn(2, 4) else 0
        val rect = Rect((cell.left + margin).coerceAtLeast(0), (cell.top + margin).coerceAtLeast(0),
            (cell.right - margin).coerceAtMost(source.width), (cell.bottom - margin).coerceAtMost(source.height))
        if (rect.width() <= 0 || rect.height() <= 0) return ""
        val crop = Bitmap.createBitmap(source, rect.left, rect.top, rect.width(), rect.height())
        var prepared: Bitmap? = null
        var bordered: Bitmap? = null
        try {
            prepared = if (enhanced) CellImageProcessing.contrast(crop) else crop
            // Border helps ML Kit read characters at the edge without widening into another cell.
            bordered = Bitmap.createBitmap(prepared.width + 32, prepared.height + 32, Bitmap.Config.ARGB_8888)
            Canvas(bordered).apply { drawColor(Color.WHITE); drawBitmap(prepared, 16f, 16f, null) }
            val text = client.process(InputImage.fromBitmap(bordered, 0)).await()
            return readingOrder(text)
        } finally {
            bordered?.recycle()
            prepared?.let { if (it !== crop && it !== source && !it.isRecycled) it.recycle() }
            if (crop !== source && !crop.isRecycled) crop.recycle()
        }
    }

    private fun readingOrder(text: Text): String {
        val lines = text.textBlocks.flatMap { it.lines }.filter { it.boundingBox != null }.sortedBy { it.boundingBox!!.centerY() }
        val rows = mutableListOf<MutableList<Text.Line>>()
        for (line in lines) {
            val box = line.boundingBox!!
            val row = rows.lastOrNull()?.takeIf {
                kotlin.math.abs(it.first().boundingBox!!.centerY() - box.centerY()) < box.height() * 0.5
            }
            if (row == null) rows += mutableListOf(line) else row += line
        }
        return rows.joinToString("\n") { row -> row.sortedBy { it.boundingBox!!.left }.joinToString(" ") { it.text } }
    }
}

package kr.co.investigation.manager

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.co.investigation.manager.data.InvestigationCase
import org.json.JSONArray

private data class TenantView(val name: String = "", val phone: String = "")

@Composable
fun RequestDocumentView(c: InvestigationCase, modifier: Modifier = Modifier) {
    val tenants = parseTenants(c.tenantsJson)
    Column(
        modifier
            .width(760.dp)
            .background(Color.White)
            .padding(horizontal = 34.dp, vertical = 28.dp)
    ) {
        Text(
            "조 사 의 뢰 서",
            color = Color.Black,
            fontSize = 25.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 5.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
        Box(
            Modifier
                .padding(top = 5.dp)
                .width(214.dp)
                .height(1.dp)
                .background(Color.Black)
                .align(Alignment.CenterHorizontally)
        )
        Text(
            "[ 의뢰일 : ${formatKoreanDate(c.requestDate)} ]",
            color = Color.Black,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 8.dp).align(Alignment.CenterHorizontally)
        )

        Spacer(Modifier.height(24.dp))
        Text("○관리번호 : ${c.managementNo}", style = docText())
        Spacer(Modifier.height(7.dp))
        Row(Modifier.fillMaxWidth()) {
            Text("○조사담당자 : ${c.investigator}", modifier = Modifier.weight(1.2f), style = docText())
            Text("Tel) -", modifier = Modifier.weight(.8f), style = docText(), textAlign = TextAlign.Center)
            Text("Fax) -", modifier = Modifier.weight(.8f), style = docText(), textAlign = TextAlign.End)
        }

        SectionTitle("1. 대 상 자")
        Row(Modifier.fillMaxWidth()) {
            DocCell("채무자 명", 1.25f, true, TextAlign.Center)
            DocCell(c.debtorName, 2.15f)
            DocCell("전 화 번 호", 1.25f, true, TextAlign.Center)
            DocCell(c.phone, 1.8f, align = TextAlign.Center)
            DocCell("핸드폰 번호", 1.35f, true, TextAlign.Center)
            DocCell(c.mobile, 2.2f, align = TextAlign.Center)
        }
        Row(Modifier.fillMaxWidth()) {
            DocCell("완료요청일", 1.25f, true, TextAlign.Center)
            DocCell(c.dueDate, 2.15f, align = TextAlign.Center)
            DocCell("비     고", 1.25f, true, TextAlign.Center)
            DocCell("", 5.35f)
        }

        SectionTitle("2. 의뢰 내용")
        Row(Modifier.fillMaxWidth()) {
            DocCell("조 사 구 분", 1.35f, true, TextAlign.Center)
            DocCell(c.investigationType, 4.4f)
            DocCell("대출종류", 1.35f, true, TextAlign.Center)
            DocCell(c.loanType, 2.9f, align = TextAlign.Center)
        }
        Row(Modifier.fillMaxWidth()) {
            DocCell("물건종류", 1.35f, true, TextAlign.Center)
            DocCell(c.propertyType, 4.4f)
            DocCell("", 1.35f, true, TextAlign.Center)
            DocCell("", 2.9f)
        }
        Row(Modifier.fillMaxWidth()) {
            DocCell("물건 소재지", 1.35f, true, TextAlign.Center)
            DocCell(c.propertyAddress, 8.65f)
        }
        Row(Modifier.fillMaxWidth()) {
            DocCell("물건 소유자", 1.35f, true, TextAlign.Center)
            DocCell("성   명", 1.25f, true, TextAlign.Center)
            DocCell(ownerDisplay(c), 3.15f)
            DocCell("연 락 처", 1.35f, true, TextAlign.Center)
            DocCell(c.ownerPhone, 2.9f, align = TextAlign.Center)
        }
        Row(Modifier.fillMaxWidth()) {
            DocCell("소유자 주소", 1.35f, true, TextAlign.Center)
            DocCell(c.ownerAddress, 8.65f)
        }

        for (row in 0 until 5) {
            val leftNo = row * 2 + 1
            val rightNo = leftNo + 1
            val left = tenants.getOrNull(leftNo - 1) ?: TenantView()
            val right = tenants.getOrNull(rightNo - 1) ?: TenantView()
            Row(Modifier.fillMaxWidth()) {
                DocCell("임차인${leftNo}(성명)", 1.45f, true, TextAlign.Center, 31.dp, 9.sp)
                DocCell(left.name, 1.35f, align = TextAlign.Center, minHeight = 31.dp, fontSize = 9.sp)
                DocCell("전 화 번 호", 1.25f, true, TextAlign.Center, 31.dp, 9.sp)
                DocCell(left.phone, 1.75f, align = TextAlign.Center, minHeight = 31.dp, fontSize = 9.sp)
                DocCell("임차인${rightNo}(성명)", 1.45f, true, TextAlign.Center, 31.dp, 9.sp)
                DocCell(right.name, 1.35f, align = TextAlign.Center, minHeight = 31.dp, fontSize = 9.sp)
                DocCell("전 화 번 호", 1.25f, true, TextAlign.Center, 31.dp, 9.sp)
                DocCell(right.phone, 1.75f, align = TextAlign.Center, minHeight = 31.dp, fontSize = 9.sp)
            }
        }

        SectionTitle("3. 기타요청사항")
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 112.dp)
                .border(1.dp, Color.Black)
                .padding(12.dp),
            contentAlignment = Alignment.TopStart
        ) {
            Text(c.requestNotes, color = Color.Black, fontSize = 11.sp, lineHeight = 17.sp)
        }

        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.weight(1f))
            Column(Modifier.weight(.92f)) {
                FooterLine("▷농협영업점 : ${c.branch}")
                FooterLine("▷조사의뢰자 : ${c.requester}")
                FooterLine("▷전 화 번 호 : -        팩스: -")
                FooterLine("▷신  청  인 : 농협자산관리회사", bold = true)
            }
        }
        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        color = Color.Black,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp)
    )
}

@Composable
private fun RowScope.DocCell(
    text: String,
    weight: Float,
    bold: Boolean = false,
    align: TextAlign = TextAlign.Left,
    minHeight: Dp = 36.dp,
    fontSize: TextUnit = 10.sp
) {
    Box(
        Modifier
            .weight(weight)
            .heightIn(min = minHeight)
            .border(.65.dp, Color.Black)
            .padding(horizontal = 7.dp, vertical = 7.dp),
        contentAlignment = when (align) {
            TextAlign.Center -> Alignment.Center
            TextAlign.End -> Alignment.CenterEnd
            else -> Alignment.CenterStart
        }
    ) {
        Text(
            text,
            color = Color.Black,
            fontSize = fontSize,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            textAlign = align,
            lineHeight = (fontSize.value + 4).sp
        )
    }
}

@Composable
private fun FooterLine(text: String, bold: Boolean = false) {
    Text(
        text,
        color = Color.Black,
        fontSize = 11.sp,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.SemiBold,
        modifier = Modifier.padding(vertical = 6.dp)
    )
}

private fun docText() = TextStyle(color = Color.Black, fontSize = 11.sp)

private fun ownerDisplay(c: InvestigationCase): String = buildString {
    append(c.ownerName)
    if (c.ownerResidentNo.isNotBlank()) {
        if (isNotBlank()) append("  ")
        append("(").append(c.ownerResidentNo).append(")")
    }
}

private fun formatKoreanDate(value: String): String {
    val m = Regex("(\\d{4})[-./년 ]+(\\d{1,2})[-./월 ]+(\\d{1,2})").find(value)
    return if (m != null) {
        val (y, mo, d) = m.destructured
        "${y}년 ${mo.padStart(2, '0')}월 ${d.padStart(2, '0')}일"
    } else value
}

private fun parseTenants(json: String): List<TenantView> = runCatching {
    val array = JSONArray(json)
    (0 until array.length()).take(10).map { i ->
        val obj = array.optJSONObject(i)
        if (obj == null) TenantView() else TenantView(
            name = obj.optString("name").ifBlank { obj.optString("tenantName") },
            phone = obj.optString("phone").ifBlank { obj.optString("mobile") }
        )
    }
}.getOrDefault(emptyList())

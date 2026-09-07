package kr.co.investigation.manager.ocr

import kr.co.investigation.manager.data.InvestigationCase
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TenantResultSanitizerV3514Test {
    @Test
    fun rejectsObservedLabelLikeFalseNames() {
        listOf("임차인", "임치인", "일치인", "의치인", "리초인", "전호번", "전화번호").forEach {
            assertFalse(it, TenantResultSanitizer.validTenantName(it))
        }
    }

    @Test
    fun keepsNormalKoreanNames() {
        listOf("홍길동", "김민수", "박지은", "양원영").forEach {
            assertTrue(it, TenantResultSanitizer.validTenantName(it))
        }
    }

    @Test
    fun removesBlankAndFalseRowsInsteadOfKeepingTenSlots() {
        val source = JSONArray().apply {
            put(row("의치인", ""))
            put(row("전호번", ""))
            put(row("홍길동", "010-1234-5678"))
            put(row("임치인", ""))
            repeat(6) { put(row("임차인", "")) }
        }
        val base = OcrService.OcrResult(
            rawText = "",
            parsed = InvestigationCase(year = 2026, tenantsJson = source.toString()),
            normalized = true,
            preprocessMessage = ""
        )

        val fixed = TenantResultSanitizer.repair(base)
        val tenants = JSONArray(fixed.parsed.tenantsJson)

        assertEquals(1, tenants.length())
        assertEquals("홍길동", tenants.getJSONObject(0).getString("name"))
        assertEquals("010-1234-5678", tenants.getJSONObject(0).getString("phone"))
    }

    @Test
    fun allowsNameWithoutPhoneButRejectsInvalidPhoneNoise() {
        val source = JSONArray().apply {
            put(row("김민수", "전화번호"))
        }
        val base = OcrService.OcrResult(
            rawText = "",
            parsed = InvestigationCase(year = 2026, tenantsJson = source.toString()),
            normalized = true,
            preprocessMessage = ""
        )

        val fixed = TenantResultSanitizer.repair(base)
        val tenant = JSONArray(fixed.parsed.tenantsJson).getJSONObject(0)
        assertEquals("김민수", tenant.getString("name"))
        assertEquals("", tenant.getString("phone"))
    }

    private fun row(name: String, phone: String) = org.json.JSONObject().apply {
        put("name", name)
        put("phone", phone)
    }
}

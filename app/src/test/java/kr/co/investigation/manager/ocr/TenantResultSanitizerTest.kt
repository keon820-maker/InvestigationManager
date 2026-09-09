package kr.co.investigation.manager.ocr

import kr.co.investigation.manager.data.InvestigationCase
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TenantResultSanitizerTest {
    @Test
    fun removesTemplateLabelsSeenOnRealDevice() {
        val input = JSONArray()
            .put(JSONObject().put("name", "지인성명").put("phone", ""))
            .put(JSONObject().put("name", "소유자").put("phone", ""))
            .put(JSONObject().put("name", "소유사").put("phone", ""))
            .put(JSONObject().put("name", "물건").put("phone", ""))
            .put(JSONObject().put("name", "수소").put("phone", ""))
            .put(JSONObject().put("name", "경기도").put("phone", "010-1234-5678"))

        val repaired = TenantResultSanitizer.repair(resultWithTenants(input.toString()))
        val tenants = JSONArray(repaired.parsed.tenantsJson)

        assertEquals(0, tenants.length())
    }

    @Test
    fun dropsWholeRowWhenLabelHasValidPhoneFromAnotherField() {
        val input = JSONArray()
            .put(JSONObject().put("name", "지인성명").put("phone", "031-768-4432"))
            .put(JSONObject().put("name", "물건").put("phone", "010-1234-5678"))
            .put(JSONObject().put("name", "경기도").put("phone", "010-5555-1111"))

        val repaired = TenantResultSanitizer.repair(resultWithTenants(input.toString()))
        val tenants = JSONArray(repaired.parsed.tenantsJson)

        assertEquals(0, tenants.length())
    }

    @Test
    fun dropsPhoneOnlyTenantRows() {
        val input = JSONArray()
            .put(JSONObject().put("name", "").put("phone", "010-1234-5678"))

        val repaired = TenantResultSanitizer.repair(resultWithTenants(input.toString()))
        val tenants = JSONArray(repaired.parsed.tenantsJson)

        assertEquals(0, tenants.length())
    }

    @Test
    fun keepsRealTenantAndDropsLeakedOwnerLabel() {
        val input = JSONArray()
            .put(JSONObject().put("name", "홍길동").put("phone", "010-1234-5678"))
            .put(JSONObject().put("name", "소유사").put("phone", ""))

        val repaired = TenantResultSanitizer.repair(resultWithTenants(input.toString()))
        val tenants = JSONArray(repaired.parsed.tenantsJson)

        assertEquals(1, tenants.length())
        assertEquals("홍길동", tenants.getJSONObject(0).getString("name"))
        assertEquals("010-1234-5678", tenants.getJSONObject(0).getString("phone"))
    }

    @Test
    fun rejectsRoleFieldAndRegionLabelsButKeepsPlausibleRealNames() {
        val labels = listOf(
            "소유자", "소유주", "소유사", "채무자", "지인성명", "임대인", "세입자",
            "전화번호", "성명", "본인거주", "물건", "물건소유자", "수소", "경기도"
        )
        labels.forEach { label ->
            assertTrue("label must be rejected: $label", !TenantResultSanitizer.validTenantName(label))
        }

        assertTrue(TenantResultSanitizer.validTenantName("김민수"))
        assertTrue(TenantResultSanitizer.validTenantName("소유진"))
    }

    private fun resultWithTenants(tenantsJson: String): OcrService.OcrResult = OcrService.OcrResult(
        rawText = "",
        parsed = InvestigationCase(year = 2026, tenantsJson = tenantsJson),
        normalized = true,
        preprocessMessage = ""
    )
}

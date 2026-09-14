package kr.co.investigation.manager.ocr

import kr.co.investigation.manager.data.InvestigationCase
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fabricated entity names, masked identifiers and telephone numbers only. */
class CorporateTenantOcrV3532Test {
    @Test
    fun debtorKeepsWholeWrappedCompanyAndIdentityMask() {
        assertEquals(
            "주식회사가상테크노로지(990101-*)",
            GridCellValues.identity("주식회사 가상테크노\n로지\n(990101-*)")
        )
        assertEquals("주식회사가상테스트(990101-*)", OcrFieldNormalizer.debtorIdentity(
            "채무자 명 주식회사 가상테스트 (990101-*)"
        ))
        assertEquals("가나다(900101-*)", GridCellValues.identity("가 나 다\n(900101-*)"))
        assertEquals("주식회사가상테스트", OcrFieldNormalizer.debtorIdentity(
            "채무자명 주식회사 가상테스트 전화번호 010-0000-1111"
        ))
    }

    @Test
    fun corporateAbbreviationsAndAlphanumericsAreNotTreatedAsResidentParentheses() {
        assertEquals("(주)가상ABC2(990101-*)", GridCellValues.identity("（주） 가상 ABC2\n(990101-*)"))
        assertEquals("가상ABC-2(주)", GridCellValues.name("가상 ABC-2 (주)"))
        assertEquals("㈜가상테스트", GridCellValues.name("㈜ 가상 테스트"))
        assertEquals("유한회사가상테스트", GridCellValues.name("유한회사 가상테스트"))
        assertEquals("(주)가상ABC2", OcrFieldNormalizer.withoutIdentity("(주)가상ABC2(990101-*)"))
    }

    @Test
    fun fullCompanyCanExtendAClippedPrefixOnlyWithMatchingIdentity() {
        assertEquals("주식회사가상테크노로지(990101-*)", OcrFieldNormalizer.preferDebtor(
            "주식회사가상(990101-*)", "주식회사 가상테크노로지(990101-*)"
        ))
        assertEquals("주식회사가상(990101-*)", OcrFieldNormalizer.preferDebtor(
            "주식회사가상(990101-*)", "주식회사 가상테크노로지(880202-*)"
        ))
        assertEquals("주식회사가상테크노로지(990101-*)", OcrFieldNormalizer.preferDebtor(
            "주식회사가상테크노로지(990101-*)", "주식회사가상(990101-*)"
        ))
        assertEquals("가상", OcrFieldNormalizer.preferDebtor("가상", "가상주식회사"))
        assertEquals("주식회사가상테크노로지", OcrFieldNormalizer.preferDebtor(
            "주식회사가상테크노로지", "주식회사가상(990101-*)"
        ))
    }

    @Test
    fun tenantValuePreservesLongCorporationButNotBareLegalLabelsOrSpilledFields() {
        assertEquals("주식회사가상테크노로지", GridCellValues.name("주식회사 가상테크노\n로지"))
        assertEquals("", GridCellValues.name("주식회사"))
        assertEquals("", GridCellValues.identity("주식회사 (990101-*)"))
        assertEquals("", GridCellValues.name("전화번호"))
        assertEquals("", GridCellValues.name("주식회사 가상테스트 전화번호"))
        assertFalse(TenantResultSanitizer.validTenantName("임차인1(성명)"))
        assertTrue(TenantResultSanitizer.validTenantName("주식회사 가상테크노로지"))
    }

    @Test
    fun wrappedBracketDuplicatePhoneIsRecognizedOnceWithoutGuessingConflicts() {
        assertEquals("010-0000-1111", GridCellValues.phone("01000001111[010\n00001111]"))
        assertEquals("", GridCellValues.phone("01000001111[010\n00002222]"))
    }

    @Test
    fun halfRowFallbackKeepsMultilineCompanyAndPhoneTogether() {
        val tenant = TargetTenantOcrRepair.parseTenantHalf(
            "임차인1(성명) 주식회사 가상테크노\n로지\n전화번호 01000001111[010\n00001111]"
        )
        assertEquals("주식회사가상테크노로지", tenant.name)
        assertEquals("010-0000-1111", tenant.phone)
        val empty = TargetTenantOcrRepair.parseTenantHalf("임차인2(성명) 전화번호")
        assertEquals("", empty.name)
        assertEquals("", empty.phone)
        val noisy = TargetTenantOcrRepair.parseTenantHalf("임차인1(성명) 주식회사 가상테스트 전화번 01000001111")
        assertEquals("주식회사가상테스트", noisy.name)
    }

    @Test
    fun finalTenantSanitizerKeepsCompanyAndNormalizesItsWrappedPhone() {
        val source = JSONArray().put(row("주식회사 가상테크노\n로지", "01000001111[010\n00001111]"))
            .put(row("전화번호", "010-0000-2222"))
            .put(row("가나다", ""))
        val fixed = TenantResultSanitizer.repair(result(source))
        val tenants = JSONArray(fixed.parsed.tenantsJson)
        assertEquals(2, tenants.length())
        assertEquals("주식회사가상테크노로지", tenants.getJSONObject(0).getString("name"))
        assertEquals("010-0000-1111", tenants.getJSONObject(0).getString("phone"))
        assertEquals("가나다", tenants.getJSONObject(1).getString("name"))
    }

    @Test
    fun severalExplicitCompaniesWithoutPhonesAreNotBlankedAsPersonalNameNoise() {
        val source = JSONArray().put(row("주식회사 가상가", ""))
            .put(row("유한회사 가상나", ""))
            .put(row("가상다 주식회사", ""))
        assertEquals(3, JSONArray(TenantResultSanitizer.repair(result(source)).parsed.tenantsJson).length())
        val mixed = JSONArray().put(row("주식회사 가상가", ""))
            .put(row("유한회사 가상나", ""))
            .put(row("가나다", ""))
        assertEquals(3, JSONArray(TenantResultSanitizer.repair(result(mixed)).parsed.tenantsJson).length())
    }

    @Test
    fun alreadyValidShortMobileNumbersRemainAvailable() {
        val fixed = TenantResultSanitizer.repair(result(JSONArray().put(row("가나다", "016-000-1111"))))
        assertEquals("016-000-1111", JSONArray(fixed.parsed.tenantsJson).getJSONObject(0).getString("phone"))
        assertEquals("016-000-1111", GridCellValues.phone("0160001111[0160001111]"))
    }

    @Test
    fun fallbackValidationAndLateRescuePreserveAlreadyRecognizedCorporateDebtor() {
        val name = "(주)가상ABC2(990101-*)"
        assertTrue(OcrFieldNormalizer.validDebtor(name))
        val current = InvestigationCase(year = 2026, debtorName = name)
        assertEquals(name, SpatialRescueRepairV357.mergeRescue(current, InvestigationCase(year = 2026)).debtorName)
    }

    private fun row(name: String, phone: String) = JSONObject().put("name", name).put("phone", phone)
    private fun result(tenants: JSONArray) = OcrService.OcrResult(
        rawText = "", parsed = InvestigationCase(year = 2026, tenantsJson = tenants.toString()),
        normalized = true, preprocessMessage = ""
    )
}

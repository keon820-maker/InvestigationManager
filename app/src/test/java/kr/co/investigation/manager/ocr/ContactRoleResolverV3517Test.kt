package kr.co.investigation.manager.ocr

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ContactRoleResolverV3517Test {
    @Test
    fun `debtor mobile duplicated in tenant but never kept as owner`() {
        val result = ContactRoleResolverV3517.resolve(
            currentPhone = "",
            currentMobile = "",
            currentOwnerPhone = "010-7636-5823", // v0.35.16 실기기 오배치
            tenantsJson = "[]",
            debtorName = "민경기(970507-*)",
            debtorRowPhones = listOf("01076365823"),
            mobileCellPhones = listOf("[01076365823]"),
            ownerCellPhones = listOf("01035426724"),
            tenant1Name = "민경기",
            tenant1Phones = listOf("01076365823"),
            excluded = setOf("010-5312-6436")
        )

        assertEquals("", result.debtorPhone)
        assertEquals("010-7636-5823", result.debtorMobile)
        assertEquals("010-3542-6724", result.ownerPhone)

        val tenants = JSONArray(result.tenantsJson)
        assertEquals("민경기", tenants.getJSONObject(0).getString("name"))
        assertEquals("010-7636-5823", tenants.getJSONObject(0).getString("phone"))
        assertFalse(result.tenantsJson.contains("010-5312-6436"))
    }

    @Test
    fun `tenant inherits debtor identity when same mobile is detected`() {
        val result = ContactRoleResolverV3517.resolve(
            currentPhone = "",
            currentMobile = "",
            currentOwnerPhone = "",
            tenantsJson = "[]",
            debtorName = "민경기(970507-*)",
            debtorRowPhones = listOf("010-7636-5823"),
            mobileCellPhones = emptyList(),
            ownerCellPhones = listOf("010-3542-6724"),
            tenant1Name = "",
            tenant1Phones = emptyList(),
            excluded = emptySet()
        )

        assertEquals("010-7636-5823", result.debtorMobile)
        val tenant = JSONArray(result.tenantsJson).getJSONObject(0)
        assertEquals("민경기", tenant.getString("name"))
        assertEquals("010-7636-5823", tenant.getString("phone"))
    }
}

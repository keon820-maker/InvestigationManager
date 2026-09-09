package kr.co.investigation.manager.ocr

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ContactRoleResolverV3517Test {
    @Test
    fun `debtor mobile duplicated in tenant but distinct owner cell wins`() {
        val result = ContactRoleResolverV3517.resolve(
            currentPhone = "",
            currentMobile = "",
            currentOwnerPhone = "010-7636-5823",
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
    fun `debtor mobile alone never creates an empty-table tenant`() {
        val result = ContactRoleResolverV3517.resolve(
            currentPhone = "",
            currentMobile = "",
            currentOwnerPhone = "",
            tenantsJson = "[]",
            debtorName = "홍길동(900101-*)",
            debtorRowPhones = listOf("010-1111-2222"),
            mobileCellPhones = emptyList(),
            ownerCellPhones = emptyList(),
            tenant1Name = "",
            tenant1Phones = listOf("010-1111-2222"),
            excluded = emptySet()
        )

        assertEquals("010-1111-2222", result.debtorMobile)
        assertEquals(0, JSONArray(result.tenantsJson).length())
    }

    @Test
    fun `same debtor and owner may legitimately share the same phone`() {
        val result = ContactRoleResolverV3517.resolve(
            currentPhone = "",
            currentMobile = "010-1111-2222",
            currentOwnerPhone = "",
            tenantsJson = "[]",
            debtorName = "홍길동(900101-*)",
            ownerName = "홍길동",
            debtorRowPhones = listOf("010-1111-2222"),
            mobileCellPhones = listOf("010-1111-2222"),
            ownerCellPhones = listOf("010-1111-2222"),
            tenant1Name = "",
            tenant1Phones = emptyList(),
            excluded = emptySet()
        )

        assertEquals("010-1111-2222", result.debtorMobile)
        assertEquals("010-1111-2222", result.ownerPhone)
        assertEquals(0, JSONArray(result.tenantsJson).length())
    }
}

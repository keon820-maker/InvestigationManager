package kr.co.investigation.manager

import kr.co.investigation.manager.data.InvestigationCase
import org.junit.Assert.assertEquals
import org.junit.Test

class CaseAddressTest {
    private val value = InvestigationCase(
        year = 2026,
        propertyAddress = "테스트시 임차동 1",
        ownerAddress = "테스트시 소유동 2"
    )

    @Test
    fun tenantAddressIsDefaultForOldRecords() {
        assertEquals("테스트시 임차동 1", value.defaultAddress())
        assertEquals(DEFAULT_ADDRESS_TENANT, value.normalizedDefaultAddressType())
    }

    @Test
    fun ownerSelectionChangesDefaultAddress() {
        val owner = value.copy(defaultAddressType = DEFAULT_ADDRESS_OWNER)
        assertEquals("테스트시 소유동 2", owner.defaultAddress())
        assertEquals("소유자 주소", owner.defaultAddressLabel())
    }
}

package kr.co.investigation.manager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DataSheetColumnSettingsTest {
    private val labels = listOf("번호", "관리번호", "진행도", "선택 주소")

    @Test
    fun savedOrderIsRestoredAndNewColumnsAreAppended() {
        assertEquals(
            listOf("진행도", "번호", "관리번호", "선택 주소"),
            normalizeColumnOrderV36("진행도|번호|관리번호", labels)
        )
    }

    @Test
    fun unknownAndDuplicateColumnsAreIgnored() {
        assertEquals(
            listOf("관리번호", "번호", "진행도", "선택 주소"),
            normalizeColumnOrderV36("관리번호|없는열|관리번호|번호", labels)
        )
    }

    @Test
    fun movingColumnChangesOnlyRequestedPosition() {
        assertEquals(
            listOf("관리번호", "번호", "진행도", "선택 주소"),
            moveColumnV36(labels, 1, 0)
        )
    }

    @Test
    fun atLeastOneVisibleColumnIsPreservedWhenLoadingPreferences() {
        val hidden = parseHiddenColumnsV36(labels.joinToString("|"), labels)
        assertFalse(labels.all { it in hidden })
        assertTrue(hidden.size == labels.size - 1)
    }
}

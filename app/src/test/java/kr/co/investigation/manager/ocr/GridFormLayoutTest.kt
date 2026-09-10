package kr.co.investigation.manager.ocr

import org.junit.Assert.*
import org.junit.Test

class GridFormLayoutTest {
    private fun fixture(dx: Int = 0, dy: Int = 0, scale: Double = 1.0): List<GridFormLayout.Cell> {
        val counts = listOf(6, 4, 4, 4, 2, 5, 2, 8, 8, 8, 8, 8, 1)
        return counts.flatMapIndexed { row, count ->
            val y = 400 + row * 50 + (if (row >= 2) 50 else 0) + (if (row >= 12) 50 else 0)
            (0 until count).map { col ->
                GridFormLayout.Cell(((100 + col * 960 / count) * scale).toInt() + dx,
                    (y * scale).toInt() + dy, ((100 + (col + 1) * 960 / count) * scale).toInt() + dx,
                    ((y + 46) * scale).toInt() + dy)
            }
        }
    }
    @Test fun mapsAllTenTenantsAndDistinctRoleCells() {
        val layout = GridFormLayout.resolve(fixture())!!
        assertEquals(10, layout.tenants.size)
        assertTrue(layout.fields.getValue("ownerPhone").top > layout.fields.getValue("phone").bottom)
        assertTrue(layout.tenants.first().first.top > layout.fields.getValue("ownerAddress").bottom)
        assertNotEquals(layout.fields.getValue("phone"), layout.fields.getValue("mobile"))
    }
    @Test fun changingMarginsAndScaleDoesNotChangeRoles() {
        val a = GridFormLayout.resolve(fixture())!!
        val b = GridFormLayout.resolve(fixture(40, 75, 2.0))!!
        assertEquals(a.fields.keys, b.fields.keys)
        assertEquals(a.fields.getValue("phone").left * 2 + 40, b.fields.getValue("phone").left)
    }
    @Test fun missingBoundaryCannotShiftTenantColumns() {
        val cells = fixture().toMutableList()
        cells.removeAt(31)
        assertNull(GridFormLayout.resolve(cells))
    }
    @Test fun incompleteFormIsNotAccepted() {
        assertNull(GridFormLayout.resolve(fixture().dropLast(1)))
        assertNull(GridFormLayout.resolve(emptyList()))
    }
}

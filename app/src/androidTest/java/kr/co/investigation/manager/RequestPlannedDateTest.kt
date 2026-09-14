package kr.co.investigation.manager

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import kr.co.investigation.manager.data.InvestigationCase
import kr.co.investigation.manager.pdf.RequestPdf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Synthetic dates only; no private document fixtures are loaded or exported. */
class RequestPlannedDateTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    private val base = InvestigationCase(
        year = 2026,
        managementNo = "planned-date-fixture",
        requestDate = "2026-01-02",
        dueDate = "2026-01-20",
        plannedDate = "2026-01-15"
    )

    @Test fun requestFormDisplaysPlannedDateSeparatelyFromDueDateAndHandlesUnsetDate() {
        val selected = mutableStateOf(base)
        ui.runOnUiThread {
            ui.activity.setContent {
                InvestigationTheme { RequestFormScreen(selected.value, onBack = {}) }
            }
        }
        ui.onNodeWithText("조사예정일").assertIsDisplayed()
        ui.onNodeWithText(base.plannedDate).assertIsDisplayed()
        ui.onNodeWithText(base.dueDate).assertIsDisplayed()

        ui.runOnIdle { selected.value = base.copy(plannedDate = "") }
        ui.onNodeWithText("조사예정일").assertIsDisplayed()
        ui.onNodeWithText("미지정").assertIsDisplayed()
        ui.onNodeWithText(base.dueDate).assertIsDisplayed()
    }

    @Test fun pdfDateCellRendersBothAssignedAndUnsetDatesWithoutAddingPages() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = RequestPdf.create(context, base)
        var assigned: Bitmap? = null
        var unset: Bitmap? = null
        try {
            assigned = renderFirstPage(file)
            RequestPdf.create(context, base.copy(plannedDate = ""))
            unset = renderFirstPage(file)

            // Inspect inside the new row, excluding its borders and surrounding text.
            assertTrue("Planned-date label is missing", ink(assigned, 60, 236, 135, 259) > 20)
            assertTrue("Assigned planned date is missing", ink(assigned, 145, 236, 275, 259) > 20)
            assertTrue("Unset placeholder is missing", ink(unset, 145, 236, 275, 259) > 20)
            val dateBefore = pixels(assigned, 145, 236, 275, 259)
            val dateAfter = pixels(unset, 145, 236, 275, 259)
            assertFalse("PDF ignored the planned date", dateBefore.contentEquals(dateAfter))
        } finally {
            assigned?.recycle()
            unset?.recycle()
            file.delete()
        }
    }

    private fun renderFirstPage(file: File): Bitmap {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                assertEquals("A short request should still fit one A4 page", 1, renderer.pageCount)
                renderer.openPage(0).use { page ->
                    return Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888).also {
                        it.eraseColor(Color.WHITE)
                        page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }
        }
    }

    private fun pixels(bitmap: Bitmap, left: Int, top: Int, right: Int, bottom: Int): IntArray {
        val width = right - left
        return IntArray(width * (bottom - top)).also {
            bitmap.getPixels(it, 0, width, left, top, width, bottom - top)
        }
    }

    private fun ink(bitmap: Bitmap, left: Int, top: Int, right: Int, bottom: Int): Int =
        pixels(bitmap, left, top, right, bottom).count { Color.alpha(it) > 0 && Color.red(it) < 160 }
}

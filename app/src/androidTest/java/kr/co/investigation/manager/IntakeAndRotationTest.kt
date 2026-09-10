package kr.co.investigation.manager

import android.Manifest
import android.app.Activity
import android.app.Instrumentation.ActivityResult
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.provider.MediaStore
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.activity.compose.setContent
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.lifecycle.ViewModelProvider
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import kotlinx.coroutines.runBlocking
import kr.co.investigation.manager.data.AppDb
import kr.co.investigation.manager.data.Attachment
import kr.co.investigation.manager.data.InvestigationCase
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.LocalDate

/** Only synthetic app data is used. These tests never load user documents. */
class IntakeAndRotationTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private var smallId = 0L
    private var largeId = 0L

    @Before fun seedLocalUi() = runBlocking {
        context.getSharedPreferences("investigation_ui", Context.MODE_PRIVATE).edit()
            .putBoolean("v029_guide_seen", true).commit()
        InvestigatorProfileStore.save(context, InvestigatorProfile("검증담당", "01000000000"))
        val db = AppDb.get(context)
        db.clearAllTables()
        smallId = db.cases().insert(InvestigationCase(year = LocalDate.now().year, managementNo = "검사-2", debtorName = "가상가"))
        largeId = db.cases().insert(InvestigationCase(year = LocalDate.now().year, managementNo = "검사-10", debtorName = "가상나"))
        db.cases().insert(InvestigationCase(year = LocalDate.now().year, managementNo = "", debtorName = "가상다"))
        val image = File(context.filesDir, "originals/ui-fixture.jpg").apply { parentFile!!.mkdirs() }
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        db.attachments().insert(Attachment(caseId = largeId, type = "CONFIRMATION", originalName = image.name,
            localPath = image.absolutePath, mimeType = "image/jpeg", byteSize = image.length(),
            width = 32, height = 32, capturedAt = null, sha256 = "synthetic-ui-fixture"))
        ui.activityRule.scenario.recreate()
        ui.waitForIdle()
    }

    private fun openMenu(label: String) {
        ui.onNodeWithText("⋮").performClick()
        ui.onAllNodesWithText(label).onLast().performClick()
        ui.waitForIdle()
    }

    private fun back() {
        ui.runOnUiThread { ui.activity.onBackPressedDispatcher.onBackPressed() }
        ui.waitForIdle()
    }

    private fun rotate(screen: String) {
        val before = ui.activity.resources.configuration.orientation
        ui.runOnUiThread {
            ui.activity.requestedOrientation = if (before == Configuration.ORIENTATION_LANDSCAPE)
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        ui.waitUntil(20_000) {
            runCatching { ui.activity.resources.configuration.orientation != before }.getOrDefault(false)
        }
        ui.waitForIdle()
        ui.onNodeWithTag("screen-$screen").assertIsDisplayed()
    }

    private fun assertRowsBefore(first: Long, second: Long) {
        val a = ui.onNodeWithTag("sheet-row-$first").fetchSemanticsNode().boundsInRoot.top
        val b = ui.onNodeWithTag("sheet-row-$second").fetchSemanticsNode().boundsInRoot.top
        assertTrue("Sorted rows should follow the selected direction", a < b)
    }

    @Test fun sheetSortFiltersAndNestedScreensSurviveRotation() {
        openMenu("전체 데이터시트")
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("sheet-header-관리번호").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("sheet-header-연도").assertDoesNotExist()
        ui.onNodeWithTag("sheet-header-방문순서").assertDoesNotExist()
        ui.onAllNodesWithText("전체 연도").assertCountEquals(0)
        ui.onNodeWithTag("sheet-header-관리번호").performClick()
        assertRowsBefore(smallId, largeId)
        ui.onNodeWithTag("sheet-header-관리번호").performClick()
        assertRowsBefore(largeId, smallId)
        ui.onNode(hasSetTextAction()).performTextInput("검사")
        closeSoftKeyboard()
        rotate("datasheet")
        ui.onNodeWithTag("sheet-header-관리번호").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "내림차순"))
        assertRowsBefore(largeId, smallId)
        ui.onNodeWithTag("sheet-row-$largeId").performClick()
        ui.onNode(hasSetTextAction() and hasText("관리번호")).performTextReplacement("검사-10-수정")
        closeSoftKeyboard()
        rotate("detail")
        ui.onNode(hasSetTextAction() and hasText("검사-10-수정")).assertExists()
        ui.onNodeWithText("조사의뢰서", useUnmergedTree = false).performClick()
        rotate("form")
        back()
        ui.onNodeWithText("조사확인서 원본").performScrollTo().performClick()
        rotate("attachment")
        back()
        back()
        ui.onNodeWithTag("screen-datasheet").assertIsDisplayed()
        ui.onNodeWithTag("sheet-header-관리번호").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "내림차순"))
    }

    @Test fun cameraCancellationAndLocalPickerKeepRegistrationOpen() {
        ui.onNodeWithText("신규 등록").performClick()
        ui.onNode(hasSetTextAction() and hasText("관리번호")).performScrollTo().performTextInput("회전검증")
        closeSoftKeyboard()
        rotate("ocr")
        ui.onNode(hasSetTextAction() and hasText("회전검증")).assertExists()
        Intents.init()
        try {
            intending(hasAction(MediaStore.ACTION_IMAGE_CAPTURE)).respondWith(ActivityResult(Activity.RESULT_CANCELED, null))
            ui.onNodeWithText("카메라 촬영").performScrollTo().performClick()
            intended(hasAction(MediaStore.ACTION_IMAGE_CAPTURE))
            ui.onNodeWithTag("screen-ocr").assertIsDisplayed()
            val capture = Intents.getIntents().last { it.action == MediaStore.ACTION_IMAGE_CAPTURE }
            assertNotNull(capture.clipData?.getItemAt(0)?.uri)
            assertTrue(capture.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
            val requested = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
            assertFalse(requested.contains(Manifest.permission.CAMERA))
            val pick = localGalleryIntent(context)
            assertTrue(pick.getBooleanExtra(Intent.EXTRA_LOCAL_ONLY, false))
            assertEquals("image/*", pick.type)
            assertTrue(pick.`package` == "com.sec.android.gallery3d" || pick.action == Intent.ACTION_OPEN_DOCUMENT)
            intending(hasAction(pick.action)).respondWith(ActivityResult(Activity.RESULT_CANCELED, null))
            ui.onNodeWithText("갤러리에서 선택").performClick()
            intended(hasAction(pick.action))
            rotate("ocr")
        } finally { Intents.release() }
    }

    @Test fun calendarSettingsAndPatchScreensSurviveRotation() {
        for ((label, screen) in listOf("캘린더" to "calendar", "데이터·동기화" to "settings", "패치내역" to "patches")) {
            openMenu(label)
            rotate(screen)
            back()
            ui.onNodeWithTag("screen-main").assertIsDisplayed()
        }
    }

    @Test fun customLocationRequiresAnAddressAndAppearsOnTheRequestForm() {
        var saved: InvestigationCase? = null
        ui.runOnUiThread {
            ui.activity.setContent {
                InvestigationTheme {
                    var selected by remember { mutableStateOf<InvestigationCase?>(null) }
                    if(selected == null) DefaultAddressChoiceDialog(
                        value = InvestigationCase(year = 2026), onDismiss = {},
                        onSelect = { saved = it; selected = it })
                    else RequestDocumentView(selected!!, Modifier.verticalScroll(rememberScrollState()))
                }
            }
        }
        ui.onNodeWithText("직접입력").performClick()
        ui.onNodeWithText("이 위치로 저장").assertIsNotEnabled()
        ui.onNode(hasSetTextAction() and hasText("직접입력 주소")).performTextInput("검증시 직접입력로 123")
        closeSoftKeyboard()
        ui.onNodeWithText("이 위치로 저장").performClick()
        assertEquals(DEFAULT_ADDRESS_CUSTOM, saved!!.defaultAddressType)
        assertEquals("검증시 직접입력로 123", saved!!.defaultAddress())
        ui.onNodeWithText("지도 표시 주소(직접입력)").assertExists()
        ui.onNodeWithText("검증시 직접입력로 123").assertExists()
    }

    @Test fun columnFiltersAndSearchPrintOnlyTheirIntersectionInTheCurrentOrder() {
        var printed: SheetPrintSnapshot? = null
        ui.runOnUiThread {
            val vm = ViewModelProvider(ui.activity)[AppViewModel::class.java]
            ui.activity.setContent { InvestigationTheme {
                DataSheetScreenV31(vm, onBack = {}, onOpen = {}, onPrint = { printed = it })
            } }
        }
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("sheet-header-관리번호").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("sheet-header-관리번호").performClick().performClick()
        ui.onNodeWithTag("sheet-filter-관리번호").performClick()
        ui.onNodeWithText("전체 해제").performClick()
        ui.onNodeWithTag("sheet-filter-value-검사-2").performClick()
        ui.onNodeWithTag("sheet-filter-value-검사-10").performClick()
        ui.onNodeWithText("적용").performClick()
        ui.onNodeWithTag("sheet-print").performClick()
        val management = printed!!.columns.indexOfFirst { it.title == "관리번호" }
        assertEquals(listOf("검사-10", "검사-2"), printed!!.rows.map { it[management] })
        assertFalse(printed!!.columns.any { it.title in setOf("연도", "방문순서") })
        ui.onNodeWithTag("sheet-search").performTextInput("검사-10")
        closeSoftKeyboard()
        ui.onNodeWithTag("sheet-print").performClick()
        assertEquals(listOf("검사-10"), printed!!.rows.map { it[management] })
        ui.onNodeWithTag("sheet-search").performTextReplacement("가상다")
        closeSoftKeyboard()
        ui.onNodeWithTag("sheet-print").assertIsNotEnabled()
        ui.onNodeWithTag("sheet-filter-관리번호").assertExists()
        ui.onNodeWithTag("sheet-search").performTextClearance()
        closeSoftKeyboard()
        ui.onNodeWithTag("sheet-print").performClick()
        assertEquals(2, printed!!.rows.size)
    }

    @Test fun sheetDeletionRequiresSelectionKeepsItOnRotationAndRetainsOriginals() {
        openMenu("전체 데이터시트")
        ui.onNodeWithText("삭제").performClick()
        ui.onNodeWithTag("sheet-delete-selected").assertIsNotEnabled()
        ui.onNodeWithTag("sheet-select-$largeId").performClick()
        rotate("datasheet")
        ui.onNodeWithTag("sheet-select-$largeId").assertIsOn()
        ui.onNodeWithTag("sheet-search").performTextInput("검사-2")
        closeSoftKeyboard()
        ui.onNodeWithTag("sheet-delete-selected").assertIsNotEnabled()
        ui.onNodeWithTag("sheet-search").performTextClearance()
        closeSoftKeyboard()
        ui.onNodeWithTag("sheet-select-$largeId").performClick()
        ui.onNodeWithTag("sheet-delete-selected").performClick()
        ui.onAllNodesWithText("취소").onLast().performClick()
        assertNull(runBlocking { AppDb.get(context).cases().get(largeId)!!.deletedAt })
        ui.onNodeWithTag("sheet-delete-selected").performClick()
        ui.onNodeWithTag("sheet-confirm-delete").performClick()
        ui.waitUntil(10_000) { runBlocking { AppDb.get(context).cases().get(largeId)!!.deletedAt != null } }
        assertNull(runBlocking { AppDb.get(context).cases().get(smallId)!!.deletedAt })
        val attachments = runBlocking { AppDb.get(context).attachments().getForCase(largeId) }
        assertEquals(1, attachments.size)
        assertTrue(File(attachments.single().localPath).isFile)
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("sheet-row-$largeId").fetchSemanticsNodes().isEmpty() }
    }

    @Test fun ordinarySaveDoesNotAskForLocationAndAddressChangesUseTheirOwnButton() {
        openMenu("전체 데이터시트")
        ui.onNodeWithTag("sheet-row-$smallId").performClick()
        ui.onNode(hasSetTextAction() and hasText("관리번호")).performTextReplacement("검사-저장")
        closeSoftKeyboard()
        ui.onNodeWithText("변경 저장").performScrollTo().performClick()
        ui.onNodeWithText("이 위치로 저장").assertDoesNotExist()
        ui.waitUntil(10_000) { runBlocking { AppDb.get(context).cases().get(smallId)!!.managementNo == "검사-저장" } }
        ui.onNodeWithText("주소지 변경").performScrollTo().performClick()
        ui.onNodeWithText("직접입력").performClick()
        ui.onNode(hasSetTextAction() and hasText("직접입력 주소")).performTextInput("검증시 변경주소 456")
        closeSoftKeyboard()
        ui.onNodeWithText("적용").performClick()
        rotate("detail")
        ui.runOnUiThread {
            val draft = ViewModelProvider(ui.activity)[AppViewModel::class.java].detailDraft.value
            assertEquals(DEFAULT_ADDRESS_CUSTOM, draft.defaultAddressType)
            assertEquals("검증시 변경주소 456", draft.customMapAddress)
        }
        // Until ordinary Save, the persistent record keeps the old address.
        assertEquals("", runBlocking { AppDb.get(context).cases().get(smallId)!!.customMapAddress })
    }
}

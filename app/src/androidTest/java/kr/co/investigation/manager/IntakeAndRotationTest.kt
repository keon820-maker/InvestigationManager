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
import android.os.ParcelFileDescriptor
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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.LocalDate

/** Only synthetic app data is used. These tests never load user documents. */
class IntakeAndRotationTest {
    @get:Rule(order = 0) val seed = object: ExternalResource() {
        override fun before() { seedLocalUi() }
    }
    @get:Rule(order = 1) val ui = createAndroidComposeRule<MainActivity>()
    @get:Rule(order = 2) val evidence = object: TestWatcher() {
        override fun failed(error: Throwable, description: Description) {
            val directory = File(context.getExternalFilesDir(null),"ui-test-evidence").apply { mkdirs() }
            runCatching {
                val roots = ui.onAllNodes(isRoot(), useUnmergedTree = true)
                File(directory,"${description.methodName}.txt").writeText(
                    roots.fetchSemanticsNodes().indices.joinToString("\n") { roots[it].printToString() })
            }
            runCatching {
                InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
                    File(directory,"${description.methodName}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
                    bitmap.recycle()
                }
            }
        }
    }
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private var smallId = 0L
    private var largeId = 0L

    private fun seedLocalUi() = runBlocking {
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
    }

    private fun openMenu(label: String) {
        ui.onNodeWithTag("main-menu").performClick()
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
        ui.onNodeWithTag("new-registration").performClick()
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
        ui.onNodeWithTag("detail-save").assertIsDisplayed().performClick()
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

    @Test fun phoneSaveRemainsVisibleAndClickableWithTheKeyboardOpen() {
        fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        ).bufferedReader().use { it.readText().trim() }
        val previousIme = shell("settings get secure show_ime_with_hard_keyboard")
        try {
            shell("wm size 1080x2400")
            shell("wm density 420")
            shell("settings put secure show_ime_with_hard_keyboard 1")
            ui.runOnUiThread { ui.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            ui.waitUntil(20_000) { ui.activity.resources.configuration.screenWidthDp in 320..480 }
            // A cached synthetic address also verifies that ordinary text edits preserve its marker.
            runBlocking {
                val dao = AppDb.get(context).cases()
                dao.update(dao.get(smallId)!!.copy(propertyAddress="검증시 기존주소 1",propertyLatitude=10.0,propertyLongitude=20.0))
            }
            openMenu("전체 데이터시트")
            ui.onNodeWithTag("sheet-row-$smallId").performClick()
            ui.onNode(hasSetTextAction() and hasText("관리번호")).performScrollTo().performClick()
                .performTextReplacement("검사-휴대폰저장")
            ui.waitUntil(15_000) {
                ViewCompat.getRootWindowInsets(ui.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
            }
            ui.onNodeWithTag("detail-save").assertIsDisplayed().assertIsEnabled().performClick()
            ui.waitUntil(10_000) { runBlocking { AppDb.get(context).cases().get(smallId)!!.managementNo == "검사-휴대폰저장" } }
            ui.onNodeWithTag("detail-save-status").assertTextEquals("저장했습니다.")
            val saved = runBlocking { AppDb.get(context).cases().get(smallId)!! }
            assertEquals(10.0,saved.propertyLatitude!!,0.0)
            assertEquals(20.0,saved.propertyLongitude!!,0.0)
        } finally {
            closeSoftKeyboard()
            shell(if(previousIme == "null") "settings delete secure show_ime_with_hard_keyboard" else "settings put secure show_ime_with_hard_keyboard $previousIme")
            shell("wm size reset")
            shell("wm density reset")
        }
    }

    @Test fun editPlannedDatePersistsAfterRotationAndCanBeCleared() {
        val originalDate = LocalDate.now().withDayOfMonth(10)
        val changedDate = originalDate.withDayOfMonth(15).toString()
        runBlocking {
            val dao = AppDb.get(context).cases()
            dao.update(dao.get(smallId)!!.copy(plannedDate=originalDate.toString(),routeOrder=7))
        }
        openMenu("전체 데이터시트")
        ui.onNodeWithTag("sheet-row-$smallId").performClick()
        ui.onNodeWithTag("planned-date-open").performScrollTo().performClick()
        ui.onNodeWithTag("planned-date-confirm").assertIsDisplayed()
        // Material DatePicker sets SemanticsProperties.Text to the full localized
        // date and clears the child day number; it does not use contentDescription.
        ui.onAllNodes(hasText("15", substring=true) and hasClickAction()).onLast().performClick()
        ui.onNodeWithTag("planned-date-confirm").performClick()
        // Editing remains a draft until Save, and survives a configuration change.
        assertEquals(originalDate.toString(),runBlocking { AppDb.get(context).cases().get(smallId)!!.plannedDate })
        rotate("detail")
        ui.onNodeWithTag("detail-save").performClick()
        ui.waitUntil(10_000) { runBlocking { AppDb.get(context).cases().get(smallId)!!.plannedDate == changedDate } }
        assertEquals(0,runBlocking { AppDb.get(context).cases().get(smallId)!!.routeOrder })
        back()
        ui.onNodeWithTag("sheet-row-$smallId").performClick()
        ui.onNodeWithTag("planned-date-open").performScrollTo().performClick()
        ui.onNodeWithTag("planned-date-clear").performClick()
        ui.onNodeWithTag("detail-save").performClick()
        ui.waitUntil(10_000) { runBlocking { AppDb.get(context).cases().get(smallId)!!.plannedDate.isBlank() } }
    }

    @Test fun patchHistoryAndOcrDiagnosticsNeverPopulateRegistrationFields() {
        openMenu("패치내역")
        back()
        ui.onNodeWithTag("new-registration").performClick()
        val diagnostics = "고정양식 검증 / 기타요청사항 보정 v0.00 / 인식 품질 16/16"
        ui.runOnUiThread {
            val draft = ViewModelProvider(ui.activity)[AppViewModel::class.java].ocrDraft
            assertEquals("",draft.parsed.value.requestNotes)
            assertEquals("",draft.parsed.value.managementNo)
            draft.parsed.value = draft.parsed.value.copy(managementNo="진단분리-검사",requestNotes="방문 전 연락")
            draft.preprocess.value = diagnostics
            draft.statusMessage.value = "인식 완료. 입력 내용을 확인해주세요."
        }
        ui.onNodeWithTag("ocr-status").assertTextEquals("인식 완료. 입력 내용을 확인해주세요.")
        ui.onNodeWithText(diagnostics).assertDoesNotExist()
        ui.onNodeWithTag("ocr-diagnostics-toggle").performScrollTo().performClick()
        ui.onNodeWithText(diagnostics).assertExists()
        ui.onNodeWithTag("ocr-diagnostics-toggle").performScrollTo().performClick()
        rotate("ocr")
        ui.onNodeWithText(diagnostics).assertDoesNotExist()
        ui.runOnUiThread {
            val draft = ViewModelProvider(ui.activity)[AppViewModel::class.java].ocrDraft
            assertEquals("진단분리-검사",draft.parsed.value.managementNo)
            assertEquals("방문 전 연락",draft.parsed.value.requestNotes)
        }
    }

    @Test fun scheduleNumberAndRegistrationSortSurviveRotation() {
        fun choose(option: String) {
            ui.onNodeWithTag("schedule-sort").performClick()
            ui.onNodeWithTag("schedule-sort-$option").performClick()
        }
        fun assertOrder(first: Long, second: Long) {
            val list = ui.onNodeWithTag("schedule-list")
            list.performScrollToNode(hasTestTag("schedule-case-$first"))
            val a = ui.onNodeWithTag("schedule-case-$first").fetchSemanticsNode().boundsInRoot.top
            val b = ui.onNodeWithTag("schedule-case-$second").fetchSemanticsNode().boundsInRoot.top
            assertTrue("Selected sort must order schedule cards",a < b)
        }
        runBlocking {
            val dao=AppDb.get(context).cases()
            dao.update(dao.get(smallId)!!.copy(createdAt=3000L))
            dao.update(dao.get(largeId)!!.copy(createdAt=1000L))
        }
        ui.onNode(hasSetTextAction()).performTextInput("검사")
        closeSoftKeyboard()
        choose("NUMBER_ASC")
        assertOrder(smallId,largeId)
        choose("NUMBER_DESC")
        assertOrder(largeId,smallId)
        choose("REGISTERED_ASC")
        assertOrder(largeId,smallId)
        rotate("main")
        assertOrder(largeId,smallId)
        choose("REGISTERED_DESC")
        assertOrder(smallId,largeId)
    }

    @Test fun existingDuplicatedNotesChangeOnlyAfterCleanupAndSave() {
        val original="검증 담당자와 통화 후 방문\n기타요청사항\n검증 담당자와 통화 후 방문\n추가 사진 확인"
        val cleaned="검증 담당자와 통화 후 방문\n추가 사진 확인"
        runBlocking {
            val dao=AppDb.get(context).cases()
            dao.update(dao.get(smallId)!!.copy(requestNotes=original))
        }
        openMenu("전체 데이터시트")
        ui.onNodeWithTag("sheet-row-$smallId").performClick()
        ui.onNodeWithTag("notes-clean-duplicates").performScrollTo().performClick()
        assertEquals(original,runBlocking { AppDb.get(context).cases().get(smallId)!!.requestNotes })
        ui.runOnUiThread {
            assertEquals(cleaned,ViewModelProvider(ui.activity)[AppViewModel::class.java].detailDraft.value.requestNotes)
        }
        ui.onNodeWithTag("detail-save").performClick()
        ui.waitUntil(10_000) { runBlocking { AppDb.get(context).cases().get(smallId)!!.requestNotes == cleaned } }
    }
}

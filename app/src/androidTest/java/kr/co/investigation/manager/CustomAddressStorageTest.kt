package kr.co.investigation.manager

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kr.co.investigation.manager.data.AppDb
import kr.co.investigation.manager.data.InvestigationCase
import kr.co.investigation.manager.pdf.RequestPdf
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CustomAddressStorageTest {
    @Test fun versionSixUpgradeKeepsExistingRecordsAndPersistsCustomAddress() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "address-migration-test.db"
        context.deleteDatabase(name)
        val before = Room.databaseBuilder(context, AppDb::class.java, name).build()
        val id = before.cases().insert(InvestigationCase(year = 2026, managementNo = "migration-fixture", propertyAddress = "검증시 원래주소 1"))
        before.close()
        // Recreate the immediately preceding schema, including a real existing row.
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("ALTER TABLE cases DROP COLUMN customMapAddress")
            db.execSQL("DELETE FROM room_master_table")
            db.version = 6
        }
        val after = Room.databaseBuilder(context, AppDb::class.java, name).addMigrations(AppDb.MIGRATION_6_7).build()
        try {
            val migrated = after.cases().get(id)!!
            assertEquals("migration-fixture", migrated.managementNo)
            assertEquals("검증시 원래주소 1", migrated.propertyAddress)
            assertEquals("", migrated.customMapAddress)
            after.cases().update(migrated.copy(defaultAddressType = DEFAULT_ADDRESS_CUSTOM, customMapAddress = "검증시 직접입력로 2"))
            assertEquals("검증시 직접입력로 2", after.cases().get(id)!!.defaultAddress())
        } finally { after.close(); context.deleteDatabase(name) }
    }

    @Test fun pdfShowsCustomAddressAndContinuesLongAddressesWithoutClipping() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val base = InvestigationCase(year = 2026, managementNo = "pdf-address-fixture")
        val file = RequestPdf.create(context, base.copy(defaultAddressType = DEFAULT_ADDRESS_CUSTOM, customMapAddress = "검증시 직접입력로 123"))
        try {
            assertTrue(inkInRegion(file, 0, 730, 790) > 100)
            val longFile = RequestPdf.create(context, base.copy(defaultAddressType = DEFAULT_ADDRESS_CUSTOM, customMapAddress = "검증시 긴주소 123 ".repeat(100)))
            ParcelFileDescriptor.open(longFile, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { assertTrue(it.pageCount > 1) }
            }
            assertTrue(inkInRegion(longFile, 1, 90, 700) > 100)
        } finally { file.delete() }
    }

    private fun inkInRegion(file: File, pageIndex: Int, top: Int, bottom: Int): Int {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                renderer.openPage(pageIndex).use { page ->
                    val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                    try {
                        bitmap.eraseColor(android.graphics.Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        var ink = 0
                        for(y in top until bottom) for(x in 50 until 545) {
                            val pixel = bitmap.getPixel(x,y)
                            if(android.graphics.Color.alpha(pixel)>0 && android.graphics.Color.red(pixel)<160) ink++
                        }
                        return ink
                    } finally { bitmap.recycle() }
                }
            }
        }
    }
}

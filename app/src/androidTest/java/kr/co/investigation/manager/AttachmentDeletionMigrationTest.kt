package kr.co.investigation.manager

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kr.co.investigation.manager.data.AppDb
import kr.co.investigation.manager.data.Attachment
import kr.co.investigation.manager.data.InvestigationCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AttachmentDeletionMigrationTest {
    @Test
    fun versionSevenUpgradeKeepsAttachmentsAndSupportsSoftDelete() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "attachment-delete-migration-test.db"
        context.deleteDatabase(name)

        val before = Room.databaseBuilder(context, AppDb::class.java, name).build()
        val caseId = before.cases().insert(InvestigationCase(year = 2026, managementNo = "attachment-fixture"))
        val attachmentId = before.attachments().insert(
            Attachment(
                caseId = caseId,
                type = "OTHER",
                originalName = "fixture.jpg",
                localPath = "/tmp/fixture.jpg",
                mimeType = "image/jpeg",
                byteSize = 10L,
                width = 10,
                height = 10,
                capturedAt = null,
                sha256 = "abc",
                cloudId = "cloud-attachment"
            )
        )
        before.close()

        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("ALTER TABLE attachments DROP COLUMN deletedAt")
            db.execSQL("DELETE FROM room_master_table")
            db.version = 7
        }

        val after = Room.databaseBuilder(context, AppDb::class.java, name)
            .addMigrations(AppDb.MIGRATION_7_8)
            .build()
        try {
            val migrated = after.attachments().getForCase(caseId).single()
            assertEquals(attachmentId, migrated.id)
            assertNull(migrated.deletedAt)

            after.attachments().update(migrated.copy(deletedAt = 123L))
            assertEquals(0, after.attachments().getForCase(caseId).size)
            assertEquals(1, after.attachments().getDeletedForCases(listOf(caseId)).size)
        } finally {
            after.close()
            context.deleteDatabase(name)
        }
    }
}

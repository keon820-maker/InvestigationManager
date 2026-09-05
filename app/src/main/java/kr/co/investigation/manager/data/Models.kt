package kr.co.investigation.manager.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "cases",
    indices = [Index("year"), Index("managementNo"), Index("propertyAddress"), Index("plannedDate"), Index("cloudId")]
)
data class InvestigationCase(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val year: Int,
    val managementNo: String = "",
    val requestDate: String = "",
    val investigator: String = "",
    val investigatorPhone: String = "",
    val investigatorFax: String = "",
    val debtorName: String = "",
    val phone: String = "",
    val mobile: String = "",
    val dueDate: String = "",
    val plannedDate: String = "",
    val routeOrder: Int = 0,
    val investigationType: String = "",
    val loanType: String = "",
    val propertyType: String = "",
    val propertyAddress: String = "",
    val propertyLatitude: Double? = null,
    val propertyLongitude: Double? = null,
    val ownerName: String = "",
    val ownerResidentNo: String = "",
    val ownerPhone: String = "",
    val ownerAddress: String = "",
    val tenantsJson: String = "[]",
    val requestNotes: String = "",
    val branch: String = "",
    val branchPhone: String = "",
    val branchFax: String = "",
    val requester: String = "",
    val investigationMemo: String = "",
    val status: String = "신규",
    val startedAt: Long? = null,
    val completedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val cloudId: String = "",
    val modifiedByDevice: String = "",
    val deletedAt: Long? = null,
    val lastSyncedAt: Long? = null
)

@Entity(tableName = "attachments", indices=[Index("caseId"), Index("cloudId")])
data class Attachment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val caseId: Long,
    val type: String,
    val originalName: String,
    val localPath: String,
    val mimeType: String,
    val byteSize: Long,
    val width: Int?,
    val height: Int?,
    val capturedAt: String?,
    val sha256: String,
    val createdAt: Long = System.currentTimeMillis(),
    val cloudId: String = "",
    val remotePath: String = "",
    val uploadedAt: Long? = null,
    val lastSyncedAt: Long? = null
)

@Dao
interface CaseDao {
    @Query("SELECT * FROM cases WHERE year=:year AND deletedAt IS NULL ORDER BY CASE WHEN plannedDate='' THEN 1 ELSE 0 END ASC, plannedDate ASC, CASE WHEN routeOrder<=0 THEN 999999 ELSE routeOrder END ASC, dueDate ASC, id DESC")
    fun observeYear(year:Int): Flow<List<InvestigationCase>>

    @Query("SELECT * FROM cases WHERE deletedAt IS NULL ORDER BY year DESC, CASE WHEN plannedDate='' THEN 1 ELSE 0 END ASC, plannedDate DESC, id DESC")
    fun observeAll(): Flow<List<InvestigationCase>>

    @Query("SELECT * FROM cases WHERE id=:id") suspend fun get(id:Long): InvestigationCase?
    @Query("SELECT * FROM cases WHERE cloudId=:cloudId LIMIT 1") suspend fun getByCloudId(cloudId:String): InvestigationCase?
    @Query("SELECT * FROM cases WHERE year=:year AND deletedAt IS NULL") suspend fun getYear(year:Int): List<InvestigationCase>
    @Query("SELECT * FROM cases") suspend fun getAllIncludingDeleted(): List<InvestigationCase>
    @Query("SELECT * FROM cases WHERE deletedAt IS NOT NULL ORDER BY deletedAt DESC") fun observeDeleted(): Flow<List<InvestigationCase>>
    @Insert suspend fun insert(value:InvestigationCase):Long
    @Update suspend fun update(value:InvestigationCase)
    @Update suspend fun updateAll(values:List<InvestigationCase>)
    @Delete suspend fun delete(value:InvestigationCase)
    @Query("DELETE FROM cases WHERE year=:year") suspend fun deleteYear(year:Int)
}

@Dao
interface AttachmentDao {
    @Query("SELECT * FROM attachments WHERE caseId=:caseId ORDER BY createdAt") fun observe(caseId:Long):Flow<List<Attachment>>
    @Query("SELECT * FROM attachments WHERE caseId IN (:caseIds)") suspend fun getForCases(caseIds:List<Long>):List<Attachment>
    @Query("SELECT * FROM attachments WHERE caseId=:caseId") suspend fun getForCase(caseId:Long):List<Attachment>
    @Query("SELECT * FROM attachments") suspend fun getAll():List<Attachment>
    @Query("SELECT * FROM attachments WHERE cloudId=:cloudId LIMIT 1") suspend fun getByCloudId(cloudId:String):Attachment?
    @Insert suspend fun insert(value:Attachment):Long
    @Update suspend fun update(value:Attachment)
    @Query("DELETE FROM attachments WHERE caseId IN (:caseIds)") suspend fun deleteForCases(caseIds:List<Long>)
    @Query("DELETE FROM attachments WHERE caseId=:caseId") suspend fun deleteForCase(caseId:Long)
}

@Database(entities=[InvestigationCase::class, Attachment::class], version=5, exportSchema=false)
abstract class AppDb: RoomDatabase() {
    abstract fun cases():CaseDao
    abstract fun attachments():AttachmentDao
    companion object {
        @Volatile private var instance:AppDb?=null

        private val MIGRATION_1_2 = object: androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cases ADD COLUMN investigatorPhone TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE cases ADD COLUMN investigatorFax TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE cases ADD COLUMN branchPhone TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE cases ADD COLUMN branchFax TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_2_3 = object: androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cases ADD COLUMN plannedDate TEXT NOT NULL DEFAULT ''")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_cases_plannedDate ON cases(plannedDate)")
            }
        }

        private val MIGRATION_3_4 = object: androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cases ADD COLUMN routeOrder INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE cases ADD COLUMN startedAt INTEGER")
                db.execSQL("ALTER TABLE cases ADD COLUMN completedAt INTEGER")
            }
        }

        private val MIGRATION_4_5 = object: androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cases ADD COLUMN cloudId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE cases ADD COLUMN modifiedByDevice TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE cases ADD COLUMN deletedAt INTEGER")
                db.execSQL("ALTER TABLE cases ADD COLUMN lastSyncedAt INTEGER")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_cases_cloudId ON cases(cloudId)")

                db.execSQL("ALTER TABLE attachments ADD COLUMN cloudId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE attachments ADD COLUMN remotePath TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE attachments ADD COLUMN uploadedAt INTEGER")
                db.execSQL("ALTER TABLE attachments ADD COLUMN lastSyncedAt INTEGER")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_attachments_cloudId ON attachments(cloudId)")
            }
        }

        fun get(context:android.content.Context):AppDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDb::class.java, "investigation.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .build()
                .also{instance=it}
        }
    }
}

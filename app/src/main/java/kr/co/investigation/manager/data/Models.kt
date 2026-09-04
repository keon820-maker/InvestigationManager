package kr.co.investigation.manager.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "cases", indices = [Index("year"), Index("managementNo"), Index("propertyAddress")])
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
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "attachments", indices=[Index("caseId")])
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
    val createdAt: Long = System.currentTimeMillis()
)

@Dao
interface CaseDao {
    @Query("SELECT * FROM cases WHERE year=:year ORDER BY dueDate ASC, id DESC") fun observeYear(year:Int): Flow<List<InvestigationCase>>
    @Query("SELECT * FROM cases WHERE id=:id") suspend fun get(id:Long): InvestigationCase?
    @Query("SELECT * FROM cases WHERE year=:year") suspend fun getYear(year:Int): List<InvestigationCase>
    @Insert suspend fun insert(value:InvestigationCase):Long
    @Update suspend fun update(value:InvestigationCase)
    @Delete suspend fun delete(value:InvestigationCase)
    @Query("DELETE FROM cases WHERE year=:year") suspend fun deleteYear(year:Int)
}

@Dao
interface AttachmentDao {
    @Query("SELECT * FROM attachments WHERE caseId=:caseId ORDER BY createdAt") fun observe(caseId:Long):Flow<List<Attachment>>
    @Query("SELECT * FROM attachments WHERE caseId IN (:caseIds)") suspend fun getForCases(caseIds:List<Long>):List<Attachment>
    @Query("SELECT * FROM attachments WHERE caseId=:caseId") suspend fun getForCase(caseId:Long):List<Attachment>
    @Insert suspend fun insert(value:Attachment):Long
    @Query("DELETE FROM attachments WHERE caseId IN (:caseIds)") suspend fun deleteForCases(caseIds:List<Long>)
    @Query("DELETE FROM attachments WHERE caseId=:caseId") suspend fun deleteForCase(caseId:Long)
}

@Database(entities=[InvestigationCase::class, Attachment::class], version=2, exportSchema=false)
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

        fun get(context:android.content.Context):AppDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDb::class.java, "investigation.db")
                .addMigrations(MIGRATION_1_2)
                .build()
                .also{instance=it}
        }
    }
}

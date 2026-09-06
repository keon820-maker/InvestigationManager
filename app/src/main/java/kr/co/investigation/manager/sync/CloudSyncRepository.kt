package kr.co.investigation.manager.sync

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Source
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kr.co.investigation.manager.data.AppDb
import kr.co.investigation.manager.data.Attachment
import kr.co.investigation.manager.data.InvestigationCase
import kr.co.investigation.manager.storage.OriginalFileStore
import java.io.File
import java.time.LocalDate
import java.util.UUID

class CloudSyncRepository(
    private val context: Context,
    private val localDb: AppDb,
    private val deviceId: String,
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val storage: FirebaseStorage = FirebaseStorage.getInstance()
) {
    suspend fun sync(uid: String): SyncSummary = withContext(Dispatchers.IO) {
        ensureCloudIds()
        val caseResult = syncCases(uid)
        val attachmentResult = syncAttachments(uid)
        SyncSummary(
            uploadedCases = caseResult.first,
            downloadedCases = caseResult.second,
            uploadedAttachments = attachmentResult.first,
            downloadedAttachments = attachmentResult.second
        )
    }

    fun watch(uid: String, onChanged: () -> Unit, onError: (Throwable) -> Unit): ListenerRegistration =
        cases(uid).addSnapshotListener { snapshot, error ->
            when {
                error != null -> onError(error)
                snapshot != null && !snapshot.metadata.hasPendingWrites() -> onChanged()
            }
        }

    private suspend fun ensureCloudIds() {
        val cases = localDb.cases().getMissingCloudIdentity().map { value ->
            value.copy(
                cloudId = value.cloudId.ifBlank { UUID.randomUUID().toString() },
                modifiedByDevice = value.modifiedByDevice.ifBlank { deviceId },
                lastSyncedAt = null
            )
        }
        val attachments = localDb.attachments().getMissingCloudId().map { value ->
            value.copy(cloudId = UUID.randomUUID().toString(), lastSyncedAt = null)
        }
        if (cases.isEmpty() && attachments.isEmpty()) return

        localDb.withTransaction {
            if (cases.isNotEmpty()) localDb.cases().updateAll(cases)
            if (attachments.isNotEmpty()) localDb.attachments().updateAll(attachments)
        }
    }

    /** Pair(uploaded, downloaded). */
    private suspend fun syncCases(uid: String): Pair<Int, Int> {
        val syncedAt = System.currentTimeMillis()
        var uploaded = 0
        var downloaded = 0
        val remoteSnapshot = cases(uid).get(Source.SERVER).await()
        val remote = remoteSnapshot.documents.associateBy { it.id }
        val localByCloudId = localDb.cases().getAllIncludingDeleted().associateBy { it.cloudId }

        remoteSnapshot.documents.forEach { document ->
            val remoteCase = document.toInvestigationCase()
            val local = localByCloudId[document.id]
            when {
                local == null -> {
                    localDb.cases().insert(remoteCase.copy(lastSyncedAt = syncedAt))
                    downloaded++
                }
                compareVersion(remoteCase, local) > 0 -> {
                    val applied = localDb.withTransaction {
                        val current = localDb.cases().get(local.id)
                        if (current != null && compareVersion(current, local) == 0) {
                            localDb.cases().update(remoteCase.copy(id = local.id, lastSyncedAt = syncedAt))
                            true
                        } else {
                            false
                        }
                    }
                    if (applied) downloaded++
                }
                compareVersion(remoteCase, local) == 0 && local.lastSyncedAt == null -> {
                    localDb.cases().update(local.copy(lastSyncedAt = syncedAt))
                }
            }
        }

        val pendingUploads = localDb.cases().getAllIncludingDeleted().filter { local ->
            val document = remote[local.cloudId]
            val remoteCase = document?.toInvestigationCase()
            remoteCase == null || compareVersion(local, remoteCase) > 0
        }
        pendingUploads.chunked(FIRESTORE_BATCH_SIZE).forEach { chunk ->
            val batch = firestore.batch()
            chunk.forEach { local ->
                batch.set(cases(uid).document(local.cloudId), local.toCloudMap())
            }
            batch.commit().await()
            markCasesSyncedIfUnchanged(chunk, syncedAt)
            uploaded += chunk.size
        }
        return uploaded to downloaded
    }

    /** Pair(uploaded, downloaded). */
    private suspend fun syncAttachments(uid: String): Pair<Int, Int> {
        var uploaded = 0
        var downloaded = 0
        val syncedAt = System.currentTimeMillis()
        val activeCases = localDb.cases().getAllActive()
        val localByCase = if (activeCases.isEmpty()) {
            emptyMap()
        } else {
            localDb.attachments().getForCases(activeCases.map { it.id }).groupBy { it.caseId }
        }

        activeCases.forEach { case ->
            val remoteSnapshot = attachmentCollection(uid, case.cloudId).get(Source.SERVER).await()
            val remoteById = remoteSnapshot.documents.associateBy { it.id }
            val localAttachments = localByCase[case.id].orEmpty().toMutableList()
            val localByCloudId = localAttachments.associateBy { it.cloudId }.toMutableMap()
            val localByFingerprint = localAttachments.associateBy { it.fingerprint() }.toMutableMap()

            remoteSnapshot.documents.forEach { document ->
                val remote = document.toRemoteAttachment()
                val exact = localByCloudId[remote.cloudId]
                val sameOriginal = exact ?: localByFingerprint[remote.fingerprint()]
                if (sameOriginal == null) {
                    val downloadedAttachment = downloadAttachment(case, remote, syncedAt)
                    val id = localDb.attachments().insert(downloadedAttachment)
                    val inserted = downloadedAttachment.copy(id = id)
                    localAttachments += inserted
                    localByCloudId[inserted.cloudId] = inserted
                    localByFingerprint[inserted.fingerprint()] = inserted
                    downloaded++
                } else {
                    val localFile = File(sameOriginal.localPath)
                    if (localFile.exists()) {
                        check(sameOriginal.sha256.equals(remote.sha256, ignoreCase = true)) {
                            "첨부 원본의 저장 해시가 클라우드와 달라 자동 덮어쓰기를 중단했습니다: ${sameOriginal.originalName}"
                        }
                        check(localFile.length() == remote.byteSize) {
                            "첨부 원본 크기가 클라우드와 달라 자동 덮어쓰기를 중단했습니다: ${sameOriginal.originalName}"
                        }
                        val updated = sameOriginal.copy(
                            cloudId = remote.cloudId,
                            remotePath = remote.storagePath,
                            uploadedAt = remote.uploadedAt,
                            lastSyncedAt = sameOriginal.lastSyncedAt ?: syncedAt
                        )
                        if (updated != sameOriginal) {
                            localDb.attachments().update(updated)
                            localAttachments.replaceById(updated)
                            localByCloudId[updated.cloudId] = updated
                            localByFingerprint[updated.fingerprint()] = updated
                        }
                    } else {
                        val updated = downloadAttachment(case, remote, syncedAt).copy(id = sameOriginal.id)
                        localDb.attachments().update(updated)
                        localAttachments.replaceById(updated)
                        localByCloudId[updated.cloudId] = updated
                        localByFingerprint[updated.fingerprint()] = updated
                        downloaded++
                    }
                }
            }

            var uploadedForCase = false
            localAttachments.forEach { local ->
                if (remoteById[local.cloudId] == null) {
                    val source = File(local.localPath)
                    check(source.exists()) { "기기에서 첨부 원본을 찾을 수 없습니다: ${local.originalName}" }
                    check(OriginalFileStore.sha256(source).equals(local.sha256, ignoreCase = true)) {
                        "첨부 원본 해시가 저장 당시와 달라 업로드를 중단했습니다: ${local.originalName}"
                    }
                    val storagePath = storagePath(uid, case.cloudId, local.cloudId)
                    val metadata = StorageMetadata.Builder()
                        .setContentType(local.mimeType)
                        .setCustomMetadata("sha256", local.sha256)
                        .setCustomMetadata("originalName", local.originalName)
                        .build()
                    storage.reference.child(storagePath).putFile(Uri.fromFile(source), metadata).await()
                    val uploadedAt = System.currentTimeMillis()
                    attachmentCollection(uid, case.cloudId).document(local.cloudId)
                        .set(local.toCloudMap(storagePath, uploadedAt)).await()
                    localDb.attachments().update(
                        local.copy(remotePath = storagePath, uploadedAt = uploadedAt, lastSyncedAt = syncedAt)
                    )
                    uploaded++
                    uploadedForCase = true
                }
            }
            if (uploadedForCase) {
                cases(uid).document(case.cloudId)
                    .update("attachmentsChangedAt", FieldValue.serverTimestamp()).await()
            }
        }
        return uploaded to downloaded
    }

    private suspend fun downloadAttachment(
        case: InvestigationCase,
        remote: RemoteAttachment,
        syncedAt: Long
    ): Attachment {
        check(remote.storagePath.isNotBlank()) { "클라우드 첨부 경로가 비어 있습니다." }
        val destination = OriginalFileStore.cloudDestination(
            context = context,
            year = case.year,
            caseId = case.id,
            cloudId = remote.cloudId,
            originalName = remote.originalName
        )
        val temporary = File(destination.parentFile, "${destination.name}.downloading")
        runCatching { temporary.delete() }
        try {
            storage.reference.child(remote.storagePath).getFile(temporary).await()
            check(temporary.length() == remote.byteSize) { "다운로드한 첨부 원본의 크기가 일치하지 않습니다." }
            check(OriginalFileStore.sha256(temporary).equals(remote.sha256, ignoreCase = true)) {
                "다운로드한 첨부 원본의 SHA-256 검증에 실패했습니다."
            }
            if (destination.exists()) destination.delete()
            check(temporary.renameTo(destination)) { "다운로드한 첨부 원본을 저장하지 못했습니다." }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
        return Attachment(
            caseId = case.id,
            type = remote.type,
            originalName = remote.originalName,
            localPath = destination.absolutePath,
            mimeType = remote.mimeType,
            byteSize = remote.byteSize,
            width = remote.width,
            height = remote.height,
            capturedAt = remote.capturedAt,
            sha256 = remote.sha256,
            createdAt = remote.createdAt,
            cloudId = remote.cloudId,
            remotePath = remote.storagePath,
            uploadedAt = remote.uploadedAt,
            lastSyncedAt = syncedAt
        )
    }

    private suspend fun markCasesSyncedIfUnchanged(values: List<InvestigationCase>, syncedAt: Long) {
        localDb.withTransaction {
            values.forEach { value ->
                val current = localDb.cases().get(value.id) ?: return@forEach
                if (current.updatedAt == value.updatedAt && current.modifiedByDevice == value.modifiedByDevice) {
                    localDb.cases().update(current.copy(lastSyncedAt = syncedAt))
                }
            }
        }
    }

    private fun cases(uid: String) = firestore.collection("users").document(uid).collection("cases")

    private fun attachmentCollection(uid: String, caseCloudId: String) =
        cases(uid).document(caseCloudId).collection("attachments")

    private fun storagePath(uid: String, caseCloudId: String, attachmentCloudId: String) =
        "users/$uid/cases/$caseCloudId/attachments/$attachmentCloudId"
}

private const val FIRESTORE_BATCH_SIZE = 400

private fun Attachment.fingerprint(): String = "${sha256.lowercase()}\u0000$type"

private fun RemoteAttachment.fingerprint(): String = "${sha256.lowercase()}\u0000$type"

private fun MutableList<Attachment>.replaceById(value: Attachment) {
    val index = indexOfFirst { it.id == value.id }
    if (index >= 0) this[index] = value else add(value)
}

internal fun compareVersion(left: InvestigationCase, right: InvestigationCase): Int {
    val time = left.updatedAt.compareTo(right.updatedAt)
    return if (time != 0) time else left.modifiedByDevice.compareTo(right.modifiedByDevice)
}

private fun InvestigationCase.toCloudMap(): Map<String, Any?> = mapOf(
    "schemaVersion" to 2,
    "year" to year,
    "managementNo" to managementNo,
    "requestDate" to requestDate,
    "investigator" to investigator,
    "investigatorPhone" to investigatorPhone,
    "investigatorFax" to investigatorFax,
    "debtorName" to debtorName,
    "phone" to phone,
    "mobile" to mobile,
    "dueDate" to dueDate,
    "plannedDate" to plannedDate,
    "routeOrder" to routeOrder,
    "investigationType" to investigationType,
    "loanType" to loanType,
    "propertyType" to propertyType,
    "propertyAddress" to propertyAddress,
    "propertyLatitude" to propertyLatitude,
    "propertyLongitude" to propertyLongitude,
    "ownerName" to ownerName,
    "ownerResidentNo" to ownerResidentNo,
    "ownerPhone" to ownerPhone,
    "ownerAddress" to ownerAddress,
    "defaultAddressType" to defaultAddressType,
    "tenantsJson" to tenantsJson,
    "requestNotes" to requestNotes,
    "branch" to branch,
    "branchPhone" to branchPhone,
    "branchFax" to branchFax,
    "requester" to requester,
    "investigationMemo" to investigationMemo,
    "status" to status,
    "startedAt" to startedAt,
    "completedAt" to completedAt,
    "createdAt" to createdAt,
    "updatedAt" to updatedAt,
    "modifiedByDevice" to modifiedByDevice,
    "deletedAt" to deletedAt,
    "serverUpdatedAt" to FieldValue.serverTimestamp()
)

private fun Attachment.toCloudMap(storagePath: String, uploadedAt: Long): Map<String, Any?> = mapOf(
    "schemaVersion" to 1,
    "type" to type,
    "originalName" to originalName,
    "mimeType" to mimeType,
    "byteSize" to byteSize,
    "width" to width,
    "height" to height,
    "capturedAt" to capturedAt,
    "sha256" to sha256,
    "createdAt" to createdAt,
    "uploadedAt" to uploadedAt,
    "storagePath" to storagePath,
    "serverUpdatedAt" to FieldValue.serverTimestamp()
)

private fun DocumentSnapshot.toInvestigationCase(): InvestigationCase {
    val data = data.orEmpty()
    return InvestigationCase(
        cloudId = id,
        year = data.int("year", LocalDate.now().year),
        managementNo = data.string("managementNo"),
        requestDate = data.string("requestDate"),
        investigator = data.string("investigator"),
        investigatorPhone = data.string("investigatorPhone"),
        investigatorFax = data.string("investigatorFax"),
        debtorName = data.string("debtorName"),
        phone = data.string("phone"),
        mobile = data.string("mobile"),
        dueDate = data.string("dueDate"),
        plannedDate = data.string("plannedDate"),
        routeOrder = data.int("routeOrder"),
        investigationType = data.string("investigationType"),
        loanType = data.string("loanType"),
        propertyType = data.string("propertyType"),
        propertyAddress = data.string("propertyAddress"),
        propertyLatitude = data.nullableDouble("propertyLatitude"),
        propertyLongitude = data.nullableDouble("propertyLongitude"),
        ownerName = data.string("ownerName"),
        ownerResidentNo = data.string("ownerResidentNo"),
        ownerPhone = data.string("ownerPhone"),
        ownerAddress = data.string("ownerAddress"),
        defaultAddressType = data.string("defaultAddressType", "TENANT"),
        tenantsJson = data.string("tenantsJson", "[]"),
        requestNotes = data.string("requestNotes"),
        branch = data.string("branch"),
        branchPhone = data.string("branchPhone"),
        branchFax = data.string("branchFax"),
        requester = data.string("requester"),
        investigationMemo = data.string("investigationMemo"),
        status = data.string("status", "신규"),
        startedAt = data.nullableLong("startedAt"),
        completedAt = data.nullableLong("completedAt"),
        createdAt = data.long("createdAt", System.currentTimeMillis()),
        updatedAt = data.long("updatedAt", System.currentTimeMillis()),
        modifiedByDevice = data.string("modifiedByDevice"),
        deletedAt = data.nullableLong("deletedAt")
    )
}

private data class RemoteAttachment(
    val cloudId: String,
    val type: String,
    val originalName: String,
    val mimeType: String,
    val byteSize: Long,
    val width: Int?,
    val height: Int?,
    val capturedAt: String?,
    val sha256: String,
    val createdAt: Long,
    val uploadedAt: Long?,
    val storagePath: String
)

private fun DocumentSnapshot.toRemoteAttachment(): RemoteAttachment {
    val data = data.orEmpty()
    return RemoteAttachment(
        cloudId = id,
        type = data.string("type"),
        originalName = data.string("originalName", "attachment"),
        mimeType = data.string("mimeType", "application/octet-stream"),
        byteSize = data.long("byteSize"),
        width = data.nullableInt("width"),
        height = data.nullableInt("height"),
        capturedAt = data["capturedAt"] as? String,
        sha256 = data.string("sha256"),
        createdAt = data.long("createdAt", System.currentTimeMillis()),
        uploadedAt = data.nullableLong("uploadedAt"),
        storagePath = data.string("storagePath")
    )
}

private fun Map<String, Any>.string(key: String, default: String = ""): String = this[key] as? String ?: default
private fun Map<String, Any>.long(key: String, default: Long = 0L): Long = when (val value = this[key]) {
    is Number -> value.toLong()
    is Timestamp -> value.toDate().time
    else -> default
}
private fun Map<String, Any>.nullableLong(key: String): Long? = when (val value = this[key]) {
    is Number -> value.toLong()
    is Timestamp -> value.toDate().time
    else -> null
}
private fun Map<String, Any>.int(key: String, default: Int = 0): Int = (this[key] as? Number)?.toInt() ?: default
private fun Map<String, Any>.nullableInt(key: String): Int? = (this[key] as? Number)?.toInt()
private fun Map<String, Any>.nullableDouble(key: String): Double? = (this[key] as? Number)?.toDouble()

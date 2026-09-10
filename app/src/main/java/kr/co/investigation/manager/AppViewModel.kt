package kr.co.investigation.manager

import android.app.Activity
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.ListenerRegistration
import kr.co.investigation.manager.data.*
import kr.co.investigation.manager.location.GeocoderService
import kr.co.investigation.manager.sync.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.util.UUID

class AppViewModel(app:Application):AndroidViewModel(app){
    val db=AppDb.get(app)
    val ocrDraft = OcrRegistrationDraft()
    val detailDraft = androidx.compose.runtime.mutableStateOf(InvestigationCase(year = LocalDate.now().year))
    val sheetFilters = androidx.compose.runtime.mutableStateOf<Map<String, Set<String>>>(emptyMap())
    val sheetFilterDraft = androidx.compose.runtime.mutableStateOf<Set<String>>(emptySet())
    val sheetSelectionMode = androidx.compose.runtime.mutableStateOf(false)
    val sheetSelectedIds = androidx.compose.runtime.mutableStateOf<Set<Long>>(emptySet())
    val sheetDeleting = androidx.compose.runtime.mutableStateOf(false)
    val sheetDeleteError = androidx.compose.runtime.mutableStateOf("")
    private val _year=MutableStateFlow(LocalDate.now().year); val year=_year.asStateFlow()
    val cases=_year.flatMapLatest{db.cases().observeYear(it)}.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val allCases=db.cases().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val deletedCases=db.cases().observeDeleted().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val _selected=MutableStateFlow<InvestigationCase?>(null); val selected=_selected.asStateFlow()
    private val geocodeAttempted = mutableSetOf<String>()
    private val syncIdentity = SyncIdentity(app)
    private val firebaseAuth = if (FirebaseBootstrap.isConfigured) FirebaseAuth.getInstance() else null
    private val syncRepository = if (FirebaseBootstrap.isConfigured) {
        CloudSyncRepository(app.applicationContext, db, syncIdentity.deviceId)
    } else null
    private val _cloudSync = MutableStateFlow(CloudSyncState(configured = FirebaseBootstrap.isConfigured))
    val cloudSync = _cloudSync.asStateFlow()
    private val syncMutex = Mutex()
    private var scheduledSyncJob: Job? = null
    private var remoteListener: ListenerRegistration? = null
    private var authListener: FirebaseAuth.AuthStateListener? = null

    init {
        viewModelScope.launch {
            cases.collect { list ->
                list.filter {
                    it.defaultAddress().isNotBlank() &&
                        (it.propertyLatitude == null || it.propertyLongitude == null)
                }.forEach { c ->
                    val address = c.defaultAddress()
                    val key = "${c.id}|${c.normalizedDefaultAddressType()}|$address"
                    if (!geocodeAttempted.add(key)) return@forEach
                    val xy = GeocoderService.resolve(getApplication(), address)
                    if (xy != null) {
                        val updated = db.withTransaction {
                            val current = db.cases().get(c.id)
                            if(current == null || current.deletedAt != null || current.defaultAddress() != address ||
                                current.normalizedDefaultAddressType() != c.normalizedDefaultAddressType()) null
                            else current.copy(propertyLatitude = xy.first, propertyLongitude = xy.second,
                                updatedAt = System.currentTimeMillis(), modifiedByDevice = syncIdentity.deviceId,
                                lastSyncedAt = null).also { db.cases().update(it) }
                        }
                        if(updated != null) {
                            if (_selected.value?.id == c.id) _selected.value = updated
                            scheduleSync()
                        }
                    }
                }
            }
        }
        firebaseAuth?.let { auth ->
            val listener = FirebaseAuth.AuthStateListener { handleAuthState(it.currentUser) }
            authListener = listener
            auth.addAuthStateListener(listener)
        }
    }

    fun setYear(y:Int){_year.value=y;_selected.value=null}
    fun select(c:InvestigationCase?){_selected.value=c; if(c != null) detailDraft.value=c}

    suspend fun create(c:InvestigationCase):Long {
        val xy=c.defaultAddress().takeIf { it.isNotBlank() }
            ?.let { GeocoderService.resolve(getApplication(),it) }
        val now = System.currentTimeMillis()
        val id = db.cases().insert(
            c.copy(
                propertyLatitude=xy?.first,
                propertyLongitude=xy?.second,
                cloudId = c.cloudId.ifBlank { UUID.randomUUID().toString() },
                modifiedByDevice = syncIdentity.deviceId,
                updatedAt = now,
                lastSyncedAt = null
            )
        )
        scheduleSync()
        return id
    }

    fun update(c:InvestigationCase){
        viewModelScope.launch{
            val xy=c.defaultAddress().takeIf { it.isNotBlank() }
                ?.let { GeocoderService.resolve(getApplication(),it) }
            val updated=c.copy(
                propertyLatitude=xy?.first,
                propertyLongitude=xy?.second,
                updatedAt=System.currentTimeMillis(),
                cloudId = c.cloudId.ifBlank { UUID.randomUUID().toString() },
                modifiedByDevice = syncIdentity.deviceId,
                lastSyncedAt = null
            )
            db.cases().update(updated)
            _selected.value=updated
            scheduleSync()
        }
    }

    fun applyInvestigatorProfile(profile: InvestigatorProfile) {
        if (!profile.isConfigured) return
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val updates = db.cases().getAllActive().map { current ->
                profile.applyTo(current).copy(
                    updatedAt = now,
                    cloudId = current.cloudId.ifBlank { UUID.randomUUID().toString() },
                    modifiedByDevice = syncIdentity.deviceId,
                    lastSyncedAt = null
                )
            }
            if (updates.isNotEmpty()) db.cases().updateAll(updates)
            val selectedId = _selected.value?.id
            if (selectedId != null) updates.firstOrNull { it.id == selectedId }?.let { _selected.value = it }
            scheduleSync()
        }
    }

    fun startInvestigation(c: InvestigationCase) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val updated = c.copy(
                status = "진행중",
                startedAt = c.startedAt ?: now,
                completedAt = null,
                updatedAt = now,
                cloudId = c.cloudId.ifBlank { UUID.randomUUID().toString() },
                modifiedByDevice = syncIdentity.deviceId,
                lastSyncedAt = null
            )
            db.cases().update(updated)
            if (_selected.value?.id == c.id) _selected.value = updated
            scheduleSync()
        }
    }

    fun completeInvestigation(c: InvestigationCase) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val updated = c.copy(
                status = "완료",
                startedAt = c.startedAt ?: now,
                completedAt = now,
                updatedAt = now,
                cloudId = c.cloudId.ifBlank { UUID.randomUUID().toString() },
                modifiedByDevice = syncIdentity.deviceId,
                lastSyncedAt = null
            )
            db.cases().update(updated)
            if (_selected.value?.id == c.id) _selected.value = updated
            scheduleSync()
        }
    }

    fun saveRouteOrder(ordered: List<InvestigationCase>) {
        if (ordered.isEmpty()) return
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val updates = ordered.mapIndexed { index, c ->
                c.copy(
                    routeOrder = index + 1,
                    updatedAt = now,
                    cloudId = c.cloudId.ifBlank { UUID.randomUUID().toString() },
                    modifiedByDevice = syncIdentity.deviceId,
                    lastSyncedAt = null
                )
            }
            db.cases().updateAll(updates)
            val selectedId = _selected.value?.id
            if (selectedId != null) updates.firstOrNull { it.id == selectedId }?.let { _selected.value = it }
            scheduleSync()
        }
    }

    suspend fun findDuplicates(c: InvestigationCase): List<InvestigationCase> {
        val management = normalizeKey(c.managementNo)
        val address = normalizeKey(c.propertyAddress)
        val debtor = normalizeKey(c.debtorName)
        return db.cases().getYear(c.year).filter { old ->
            if (old.id == c.id) return@filter false
            val managementSame = management.isNotBlank() && normalizeKey(old.managementNo) == management
            val addressDebtorSame = address.isNotBlank() && debtor.isNotBlank() &&
                normalizeKey(old.propertyAddress) == address && normalizeKey(old.debtorName) == debtor
            managementSame || addressDebtorSame
        }
    }

    suspend fun addAttachment(value: Attachment): Long {
        val attachment = value.copy(
            cloudId = value.cloudId.ifBlank { UUID.randomUUID().toString() },
            remotePath = "",
            uploadedAt = null,
            lastSyncedAt = null
        )
        val id = db.attachments().insert(attachment)
        db.cases().get(value.caseId)?.let { parent ->
            db.cases().update(
                parent.copy(
                    updatedAt = System.currentTimeMillis(),
                    cloudId = parent.cloudId.ifBlank { UUID.randomUUID().toString() },
                    modifiedByDevice = syncIdentity.deviceId,
                    lastSyncedAt = null
                )
            )
        }
        scheduleSync()
        return id
    }

    suspend fun deleteCase(c: InvestigationCase) = deleteCases(setOf(c.id))

    /** Read fresh rows and move the entire selection to the trash atomically. Originals stay intact. */
    suspend fun deleteCases(ids: Set<Long>) {
        if (ids.isEmpty()) return
        val now = System.currentTimeMillis()
        db.withTransaction {
            ids.forEach { id ->
                db.cases().get(id)?.takeIf { it.deletedAt == null }?.let { current ->
                    db.cases().update(current.copy(deletedAt = now, updatedAt = now,
                        cloudId = current.cloudId.ifBlank { UUID.randomUUID().toString() },
                        modifiedByDevice = syncIdentity.deviceId, lastSyncedAt = null))
                }
            }
        }
        if (_selected.value?.id in ids) _selected.value = null
        scheduleSync()
    }

    fun deleteSheetSelection(ids: Set<Long>) {
        if (sheetDeleting.value || ids.isEmpty()) return
        sheetDeleting.value = true
        sheetDeleteError.value = ""
        viewModelScope.launch {
            try {
                deleteCases(ids)
                sheetSelectedIds.value = emptySet()
                sheetSelectionMode.value = false
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (_: Exception) { sheetDeleteError.value = "삭제하지 못했습니다. 다시 시도해주세요." }
            finally { sheetDeleting.value = false }
        }
    }

    fun restoreCase(c: InvestigationCase) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val restored = c.copy(
                deletedAt = null,
                updatedAt = now,
                cloudId = c.cloudId.ifBlank { UUID.randomUUID().toString() },
                modifiedByDevice = syncIdentity.deviceId,
                lastSyncedAt = null
            )
            db.cases().update(restored)
            _year.value = restored.year
            scheduleSync()
        }
    }

    fun signIn(activity: Activity) {
        if (!FirebaseBootstrap.isConfigured) {
            _cloudSync.update { it.copy(message = "Firebase 연결 설정이 없어 로그인할 수 없습니다.") }
            return
        }
        viewModelScope.launch {
            _cloudSync.update { it.copy(syncing = true, message = "Google 계정 연결 중…") }
            runCatching { GoogleAccountService(activity).signIn() }
                .onSuccess { account ->
                    _cloudSync.update { it.copy(account = account, syncing = false, message = "계정 연결 완료. 동기화를 시작합니다.") }
                    syncNow()
                }
                .onFailure { error ->
                    _cloudSync.update { it.copy(syncing = false, message = friendlyError(error)) }
                }
        }
    }

    fun signOut(activity: Activity) {
        viewModelScope.launch {
            runCatching { GoogleAccountService(activity).signOut() }
                .onFailure { error -> _cloudSync.update { it.copy(message = friendlyError(error)) } }
        }
    }

    fun syncNow() {
        scheduledSyncJob?.cancel()
        scheduledSyncJob = null
        viewModelScope.launch { performSync() }
    }

    private fun scheduleSync(delayMillis: Long = 900L) {
        if (firebaseAuth?.currentUser == null) return
        scheduledSyncJob?.cancel()
        scheduledSyncJob = viewModelScope.launch {
            delay(delayMillis)
            performSync()
        }
    }

    private suspend fun performSync() {
        val user = firebaseAuth?.currentUser ?: return
        val repository = syncRepository ?: return
        syncMutex.withLock {
            runCatching {
                syncIdentity.assertOrBindOwner(user.uid)
                _cloudSync.update { it.copy(syncing = true, message = "조사 데이터와 원본을 동기화하는 중…") }
                repository.sync(user.uid)
            }.onSuccess { result ->
                _cloudSync.update {
                    it.copy(
                        account = user.toCloudAccount(),
                        syncing = false,
                        lastSuccessAt = System.currentTimeMillis(),
                        uploadedCases = result.uploadedCases,
                        downloadedCases = result.downloadedCases,
                        uploadedAttachments = result.uploadedAttachments,
                        downloadedAttachments = result.downloadedAttachments,
                        message = "모든 기기의 데이터가 최신 상태입니다."
                    )
                }
            }.onFailure { error ->
                _cloudSync.update { it.copy(syncing = false, message = friendlyError(error)) }
            }
        }
    }

    private fun handleAuthState(user: FirebaseUser?) {
        remoteListener?.remove()
        remoteListener = null
        if (user == null) {
            _cloudSync.update {
                it.copy(account = null, syncing = false, message = "Google 계정에 로그인하면 동기화가 시작됩니다.")
            }
            return
        }
        val account = user.toCloudAccount()
        _cloudSync.update { it.copy(account = account, message = "계정 연결 완료. 동기화를 준비합니다.") }
        runCatching { syncIdentity.assertOrBindOwner(user.uid) }
            .onFailure { error ->
                _cloudSync.update { it.copy(message = friendlyError(error)) }
                return
            }
        remoteListener = syncRepository?.watch(
            uid = user.uid,
            onChanged = { scheduleSync(500L) },
            onError = { error -> _cloudSync.update { it.copy(message = friendlyError(error)) } }
        )
        scheduleSync(100L)
    }

    private fun friendlyError(error: Throwable): String {
        val message = error.message.orEmpty()
        return when {
            error.javaClass.simpleName.contains("Cancellation", ignoreCase = true) -> "Google 로그인이 취소되었습니다."
            message.contains("network", ignoreCase = true) -> "네트워크 연결을 확인한 뒤 다시 동기화해 주세요."
            message.isNotBlank() -> "동기화 실패: $message"
            else -> "동기화 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요."
        }
    }

    private fun normalizeKey(value: String): String = value
        .trim()
        .lowercase()
        .replace(Regex("[\\s\\-()\\[\\],.]"), "")

    override fun onCleared() {
        remoteListener?.remove()
        authListener?.let { listener -> firebaseAuth?.removeAuthStateListener(listener) }
        super.onCleared()
    }
}

package kr.co.investigation.manager.sync

data class CloudAccount(
    val uid: String,
    val email: String,
    val displayName: String
)

data class CloudSyncState(
    val configured: Boolean,
    val account: CloudAccount? = null,
    val syncing: Boolean = false,
    val lastSuccessAt: Long? = null,
    val uploadedCases: Int = 0,
    val downloadedCases: Int = 0,
    val uploadedAttachments: Int = 0,
    val downloadedAttachments: Int = 0,
    val message: String = if (configured) "Google 계정에 로그인하면 동기화가 시작됩니다." else "Firebase 연결 설정이 필요합니다."
)

data class SyncSummary(
    val uploadedCases: Int,
    val downloadedCases: Int,
    val uploadedAttachments: Int,
    val downloadedAttachments: Int
)

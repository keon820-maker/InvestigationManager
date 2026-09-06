package kr.co.investigation.manager.sync

import android.content.Context
import java.util.UUID

class SyncIdentity(context: Context) {
    private val prefs = context.getSharedPreferences("investigation_sync", Context.MODE_PRIVATE)

    val deviceId: String
        get() = prefs.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_DEVICE_ID, it).apply()
        }

    fun assertOrBindOwner(uid: String) {
        val current = prefs.getString(KEY_OWNER_UID, null)
        when {
            current == null -> prefs.edit().putString(KEY_OWNER_UID, uid).apply()
            current != uid -> error("이 기기의 조사 데이터는 처음 연결한 Google 계정과만 동기화할 수 있습니다.")
        }
    }

    private companion object {
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_OWNER_UID = "owner_uid"
    }
}

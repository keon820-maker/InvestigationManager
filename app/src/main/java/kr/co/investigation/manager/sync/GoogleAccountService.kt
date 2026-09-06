package kr.co.investigation.manager.sync

import android.app.Activity
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.tasks.await
import kr.co.investigation.manager.BuildConfig

class GoogleAccountService(private val activity: Activity) {
    private val auth = FirebaseAuth.getInstance()
    private val credentialManager = CredentialManager.create(activity)

    suspend fun signIn(): CloudAccount {
        check(BuildConfig.FIREBASE_WEB_CLIENT_ID.isNotBlank()) { "Google 로그인 설정이 없습니다." }
        val googleOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(BuildConfig.FIREBASE_WEB_CLIENT_ID)
            .setAutoSelectEnabled(false)
            .build()
        val response = credentialManager.getCredential(
            context = activity,
            request = GetCredentialRequest.Builder().addCredentialOption(googleOption).build()
        )
        val credential = response.credential
        check(
            credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) { "선택한 계정에서 Google 인증 정보를 받지 못했습니다." }

        val googleCredential = GoogleIdTokenCredential.createFrom(credential.data)
        val result = auth.signInWithCredential(
            GoogleAuthProvider.getCredential(googleCredential.idToken, null)
        ).await()
        return requireNotNull(result.user).toCloudAccount()
    }

    suspend fun signOut() {
        auth.signOut()
        credentialManager.clearCredentialState(ClearCredentialStateRequest())
    }
}

fun FirebaseUser.toCloudAccount(): CloudAccount = CloudAccount(
    uid = uid,
    email = email.orEmpty(),
    displayName = displayName.orEmpty()
)

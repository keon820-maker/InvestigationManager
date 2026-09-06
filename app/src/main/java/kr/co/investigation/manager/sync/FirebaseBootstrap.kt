package kr.co.investigation.manager.sync

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import kr.co.investigation.manager.BuildConfig

/** Initializes Firebase from CI-injected BuildConfig values without committing app credentials. */
object FirebaseBootstrap {
    val isConfigured: Boolean
        get() = BuildConfig.FIREBASE_CONFIGURED

    fun initialize(context: Context): Boolean {
        if (!isConfigured) return false
        if (FirebaseApp.getApps(context).any { it.name == FirebaseApp.DEFAULT_APP_NAME }) return true

        val options = FirebaseOptions.Builder()
            .setApiKey(BuildConfig.FIREBASE_API_KEY)
            .setApplicationId(BuildConfig.FIREBASE_APP_ID)
            .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
            .setStorageBucket(BuildConfig.FIREBASE_STORAGE_BUCKET)
            .build()
        FirebaseApp.initializeApp(context, options)
        return true
    }
}

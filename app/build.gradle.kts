plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

val kakaoNativeAppKey = providers.environmentVariable("KAKAO_NATIVE_APP_KEY").orNull.orEmpty()
val signingKeystoreFile = providers.environmentVariable("SIGNING_KEYSTORE_FILE").orNull
val signingStorePassword = providers.environmentVariable("SIGNING_STORE_PASSWORD").orNull
val hasPermanentSigning = !signingKeystoreFile.isNullOrBlank() && !signingStorePassword.isNullOrBlank()
val firebaseApiKey = providers.environmentVariable("FIREBASE_API_KEY").orNull.orEmpty()
val firebaseAppId = providers.environmentVariable("FIREBASE_APP_ID").orNull.orEmpty()
val firebaseProjectId = providers.environmentVariable("FIREBASE_PROJECT_ID").orNull.orEmpty()
val firebaseStorageBucket = providers.environmentVariable("FIREBASE_STORAGE_BUCKET").orNull.orEmpty()
val firebaseWebClientId = providers.environmentVariable("FIREBASE_WEB_CLIENT_ID").orNull.orEmpty()
val hasFirebaseConfig = listOf(
    firebaseApiKey,
    firebaseAppId,
    firebaseProjectId,
    firebaseStorageBucket,
    firebaseWebClientId
).all { it.isNotBlank() }

fun String.asBuildConfigString(): String = "\"" +
    replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "kr.co.investigation.manager"
    compileSdk = 35

    defaultConfig {
        applicationId = "kr.co.investigation.manager"
        minSdk = 28
        targetSdk = 35
        versionCode = 33
        versionName = "0.33.0"
        buildConfigField("String", "KAKAO_NATIVE_APP_KEY", "\"$kakaoNativeAppKey\"")
        buildConfigField("boolean", "FIREBASE_CONFIGURED", hasFirebaseConfig.toString())
        buildConfigField("String", "FIREBASE_API_KEY", firebaseApiKey.asBuildConfigString())
        buildConfigField("String", "FIREBASE_APP_ID", firebaseAppId.asBuildConfigString())
        buildConfigField("String", "FIREBASE_PROJECT_ID", firebaseProjectId.asBuildConfigString())
        buildConfigField("String", "FIREBASE_STORAGE_BUCKET", firebaseStorageBucket.asBuildConfigString())
        buildConfigField("String", "FIREBASE_WEB_CLIENT_ID", firebaseWebClientId.asBuildConfigString())
    }
    signingConfigs {
        if (hasPermanentSigning) {
            create("permanent") {
                storeFile = file(signingKeystoreFile!!)
                storePassword = signingStorePassword
                keyAlias = "investigationmanager"
                keyPassword = signingStorePassword
                storeType = "PKCS12"
            }
        }
    }
    buildTypes {
        getByName("debug") {
            if (hasPermanentSigning) signingConfig = signingConfigs.getByName("permanent")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("com.google.mlkit:text-recognition-korean:16.0.1")
    implementation("org.opencv:opencv:4.10.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("com.kakao.maps.open:android:2.15.1")

    // BoM 33.13 stays compatible with this app's Kotlin 2.0 compiler.
    // Firebase Auth 24.x is built with newer Kotlin metadata and cannot be consumed safely here.
    implementation(platform("com.google.firebase:firebase-bom:33.13.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-storage")
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
    testImplementation("junit:junit:4.13.2")
}

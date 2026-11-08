import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Public client values only (URL + publishable key). Read from env (CI) or local.properties.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun cfg(name: String, default: String = ""): String =
    (System.getenv(name)?.takeIf { it.isNotBlank() } ?: localProps.getProperty(name) ?: default).trim()

android {
    namespace = "com.sonicteam.sonicremote"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sonicteam.sonicremote"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "0.5.0"

        buildConfigField("String", "SUPABASE_URL", "\"${cfg("SUPABASE_URL", "https://afbhqesmbjxelnyzxjkc.supabase.co")}\"")
        buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", "\"${cfg("SUPABASE_PUBLISHABLE_KEY")}\"")
    }

    // A fixed (non-secret) debug keystore keeps the signature identical across CI builds,
    // so a new APK installs over the old one without uninstalling.
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("keystore/sonicteam-debug.jks")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.github.webrtc-sdk:android:125.6422.07")
}

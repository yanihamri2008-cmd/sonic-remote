plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.sonicteam.sonicremote"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sonicteam.sonicremote"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        // Replace with your Supabase project URL and Publishable key.
        // These are public client values, not service-role secrets.
        buildConfigField("String", "SUPABASE_URL", "\"https://YOUR_PROJECT_ID.supabase.co\"")
        buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", "\"YOUR_PUBLISHABLE_KEY\"")
    }

    buildFeatures {
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

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.textoverlay.assistant"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.textoverlay.assistant"
        minSdk = 26
        targetSdk = 34
        versionCode = 7
        versionName = "2.5"
    }

    signingConfigs {
        // A fixed debug keystore committed to the repo so every CI build is
        // signed with the same key. This lets new builds install on top of an
        // existing install without "App not installed" signature errors.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Secure on-device storage for the API key
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // HTTP client for the Claude Messages API
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}

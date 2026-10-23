plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.example.remotecontrol"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.remotecontrol"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        signingConfig = signingConfigs.debug
        // Only update with this exact keystore!
        applicationId = "com.example.remotecontrol"
    }

    signingConfigs.release {
        storeFile = file('my-release-keystore.keystore')
        storePassword = 'your-store-password'
        keyAlias = 'my-key-alias'
        keyPassword = 'your-key-password'
    }

    signingConfigs.debug {
        storeFile = file('debug.keystore')
        storePassword = 'android'
        keyAlias = 'androiddebugkey'
        keyPassword = 'android'
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // App signing - ONLY this key can update the APK
            signingConfig = signingConfigs.release
        }

        signingConfigs.release {
            storeFile = file('my-release-keystore.keystore')
            storePassword = 'your-store-password'
            keyAlias = 'my-key-alias'
            keyPassword = 'your-key-password'
        }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)

    // Core + lifecycle
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")

    // Compose UI
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // WebSocket client
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Coroutines (reconnect/backoff, suspend command handlers)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}

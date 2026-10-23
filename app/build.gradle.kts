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
        versionCode = 2
        versionName = "1.1"
    }

    // Release signing — ONLY this keystore can update the installed app.
    // The keystore file is intentionally NOT committed to git (see .gitignore).
    // To cut a signed release: place my-release-keystore.keystore in app/
    // (or set the RELEASE_* env vars) and run ./gradlew assembleRelease.
    // While the keystore is absent, release builds fall back to the debug key
    // so CI and local builds stay green.
    signingConfigs {
        // Committed debug keystore: every build (local + CI) signs with the
        // SAME key, so updates always install over the previous APK.
        create("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }

        create("release") {
            val keystore = file("my-release-keystore.keystore")
            if (keystore.exists()) {
                storeFile = keystore
                storePassword = System.getenv("RELEASE_STORE_PASSWORD") ?: "your-store-password"
                keyAlias = System.getenv("RELEASE_KEY_ALIAS") ?: "my-key-alias"
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD") ?: "your-key-password"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val releaseSigning = signingConfigs.getByName("release")
            signingConfig = if (releaseSigning.storeFile?.exists() == true) {
                releaseSigning
            } else {
                // No release keystore present yet — sign with the standard
                // debug key so the build still produces an installable APK.
                signingConfigs.getByName("debug")
            }
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

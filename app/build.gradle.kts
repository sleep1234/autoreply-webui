plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.example.autoreply"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.example.autoreply"
        minSdk = 28
        targetSdk = 35
        versionCode = 2026092709
        versionName = "2026.09.27.9"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
}

dependencies {
    // ---- Xposed (compileOnly: provided by the framework at runtime) ----
    compileOnly("de.robv.android.xposed:api:82")

    // ---- DexKit: runtime discovery of WeChat's obfuscated methods ----
    implementation("org.luckypray:dexkit:2.2.0")

    // ---- JSON + HTTP (LLM API) ----
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // ---- Coroutines ----
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // ---- MMKV (config persistence) ----
    implementation("com.tencent:mmkv:2.4.2")

    // ---- Compose UI (settings screen) ----
    implementation(platform("androidx.compose:compose-bom:2024.10.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
}
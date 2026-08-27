plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.itantra.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.itantra.app"
        // minSdk 24 chosen to cover low/mid-range phones per PS requirement.
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1-hackathon"

        // Keep native libs unstripped for onnxruntime/vosk .so files
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // Model files (Vosk folders, .onnx TTS/VAD models) go in app/src/main/assets/models/
    // They are NOT committed here — see README for download instructions.
    aaptOptions {
        noCompress += listOf("tflite", "onnx", "task")
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
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // --- Core / Compose UI ---
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")

    // --- Coroutines (async pipeline: mic -> VAD -> STT -> BT -> TTS -> playback) ---
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // --- Offline STT: Vosk ---
    // Real published artifact. Verify latest version against
    // https://github.com/alphacep/vosk-android-demo before building.
    implementation("com.alphacephei:vosk-android:0.3.70")
    implementation("net.java.dev.jna:jna:5.14.0@aar")

    // --- Offline VAD + TTS inference runtime (ONNX models: Silero VAD, Indic-VITS TTS) ---
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.18.0")

    // --- JSON parsing for STT partial/final result payloads ---
    implementation("org.json:json:20240303")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

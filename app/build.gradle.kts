plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.itantra.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.itantra.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "0.2-offline-ml"

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

    // Neural model archives are unpacked into assets before Gradle runs.
    aaptOptions {
        noCompress += listOf("tflite", "onnx", "bin", "task")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Offline streaming STT for lightweight demo language packs.
    implementation("com.alphacephei:vosk-android:0.3.70")
    implementation("net.java.dev.jna:jna:5.14.0@aar")

    // Open-source native ONNX runtime used by Silero VAD.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.18.0")

    // Open-source Android sherpa-onnx runtime for real Piper/VITS inference.
    // It handles Piper's phonemization + ONNX model inputs instead of the previous
    // placeholder character-level VITS implementation.
    implementation("com.xdcobra.sherpa:sherpa-onnx:1.13.2-1")
    implementation("com.xdcobra.sherpa:onnxruntime:1.13.2-1")

    implementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

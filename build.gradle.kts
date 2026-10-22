import java.util.UUID

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.offlineai.touchfree"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.offlineai.touchfree"
        minSdk = 28            // GLOBAL_ACTION_TAKE_SCREENSHOT needs API 28; Galaxy A23 5G runs Android 12+
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        ndk { abiFilters += listOf("arm64-v8a") }   // Snapdragon 695 is arm64 -> smaller APK
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the debug key so the release APK is directly installable
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        jniLibs.useLegacyPackaging = true
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
    // Never compress the Vosk model files
    androidResources { noCompress += listOf("mdl", "fst", "conf", "int", "mat", "stats", "dubm", "ie", "txt") }
    lint { abortOnError = false }
}

dependencies {
    // Offline Speech-to-Text (Vosk)
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    implementation("com.alphacephei:vosk-android:0.3.47@aar")
    // Optional on-device LLM (MediaPipe GenAI)
    implementation("com.google.mediapipe:tasks-genai:0.10.14")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}

// Vosk's StorageService needs a "uuid" file inside the model folder to know when to re-unpack it.
tasks.register("genModelUuid") {
    doLast {
        val dir = file("src/main/assets/model-en")
        if (dir.exists() && (dir.listFiles()?.any { it.name != "uuid" && it.name != "README.txt" } == true)) {
            File(dir, "uuid").writeText(UUID.randomUUID().toString())
        }
    }
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn("genModelUuid") }

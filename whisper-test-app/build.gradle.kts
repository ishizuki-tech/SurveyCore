plugins {
    alias(libs.plugins.android.application)
}


val sherpaOnnxAar =
    file(
        System.getenv("SURVEYCORE_SHERPA_AAR")
            ?: "${System.getProperty("user.home")}/.cache/surveycore/libs/sherpa-onnx-1.13.4.aar"
    )

android {
    namespace = "com.negi.whispertest"

    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.negi.whispertest"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        debug {
            // Default debug signing is used.
        }

        release {
            // For local device testing only.
            // This signs the release APK with the debug keystore.
            signingConfig = signingConfigs.getByName("debug")

            // Keep shrinking disabled until Whisper/JNI release behavior is verified.
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":asr-whispercpp"))
    implementation(files(sherpaOnnxAar))

    implementation(
        "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0"
    )
}

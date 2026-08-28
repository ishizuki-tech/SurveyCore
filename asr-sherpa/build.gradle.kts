plugins {
    alias(libs.plugins.android.library)
}

val sherpaOnnxAar =
    file(
        System.getenv("SURVEYCORE_SHERPA_AAR")
            ?: "${System.getProperty("user.home")}/.cache/surveycore/libs/sherpa-onnx-1.13.4.aar"
    )

android {
    namespace = "com.negi.surveycore.asr.sherpa"

    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 28
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // The final app packages the AAR. This module only needs it to compile.
    compileOnly(files(sherpaOnnxAar))
}

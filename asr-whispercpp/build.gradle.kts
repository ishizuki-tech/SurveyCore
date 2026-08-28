plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.negi.surveycore.asr.whispercpp"

    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 28

        ndk {
            abiFilters +=
                listOf(
                    "arm64-v8a"
                )
        }

    }

    externalNativeBuild {
        cmake {
            path =
                file(
                    "src/main/cpp/CMakeLists.txt"
                )

            version =
                "3.22.1"
        }
    }


    compileOptions {
        sourceCompatibility =
            JavaVersion.VERSION_11

        targetCompatibility =
            JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(
        "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0"
    )

    testImplementation(
        libs.junit
    )
}

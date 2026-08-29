plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val sherpaOnnxAar =
    file(
        System.getenv("SURVEYCORE_SHERPA_AAR")
            ?: "${System.getProperty("user.home")}/.cache/surveycore/libs/sherpa-onnx-1.13.4.aar"
    )


fun surveyCoreSigningValue(
    name: String,
): String? =
    providers
        .gradleProperty(name)
        .orNull
        ?: System.getenv(name)

val surveyCoreSigningStoreFile =
    surveyCoreSigningValue(
        "SURVEYCORE_SIGNING_STORE_FILE"
    )

val surveyCoreSigningStorePassword =
    surveyCoreSigningValue(
        "SURVEYCORE_SIGNING_STORE_PASSWORD"
    )

val surveyCoreSigningKeyAlias =
    surveyCoreSigningValue(
        "SURVEYCORE_SIGNING_KEY_ALIAS"
    )

val surveyCoreSigningKeyPassword =
    surveyCoreSigningValue(
        "SURVEYCORE_SIGNING_KEY_PASSWORD"
    )

val surveyCoreInternalSigningReady =
    listOf(
        surveyCoreSigningStoreFile,
        surveyCoreSigningStorePassword,
        surveyCoreSigningKeyAlias,
        surveyCoreSigningKeyPassword,
    ).all {
        !it.isNullOrBlank()
    }


android {
    namespace = "com.negi.surveycore"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.negi.surveycore"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

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

    signingConfigs {
        if (
            surveyCoreInternalSigningReady
        ) {
            create(
                "internalRelease"
            ) {
                storeFile =
                    file(
                        checkNotNull(
                            surveyCoreSigningStoreFile
                        )
                    )

                storePassword =
                    checkNotNull(
                        surveyCoreSigningStorePassword
                    )

                keyAlias =
                    checkNotNull(
                        surveyCoreSigningKeyAlias
                    )

                keyPassword =
                    checkNotNull(
                        surveyCoreSigningKeyPassword
                    )
            }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
            signingConfig =
                signingConfigs.findByName(
                    "internalRelease"
                )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }

    androidResources {
        // sherpa-onnx loads these directly from AssetManager.
        noCompress += listOf("onnx", "txt", "bin")
    }
}

dependencies {
    implementation(project(":asr-sherpa"))
    implementation(files(sherpaOnnxAar))

    implementation(
        project(":asr-whispercpp")
    )

    implementation(
        "com.google.ai.edge.litertlm:litertlm-android:0.16.1"
    )
    implementation(
        "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0"
    )

    implementation("org.yaml:snakeyaml:2.6")
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
import java.util.Base64

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "io.github.enricof64.scan"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.enricof64.scan"
        minSdk = 26
        targetSdk = 36
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Opt-in telemetry endpoint (docs/PRIVACY.md). Both blank by default:
        // without them the built APK ships an inert ".invalid" endpoint and
        // uploads fail fast even when the user opts in. Never commit real
        // values — CI reads them from repo secrets, locals from
        // ~/.gradle/gradle.properties.
        buildConfigField(
            "String",
            "TELEMETRY_UPLOAD_URL",
            "\"${telemetryConfigValue("OPENSCAN_TELEMETRY_UPLOAD_URL")}\"",
        )
        buildConfigField(
            "String",
            "TELEMETRY_TOKEN",
            "\"${telemetryConfigValue("OPENSCAN_TELEMETRY_UPLOAD_TOKEN")}\"",
        )

        // OpenCV ships x86/x86_64 libs for emulators; packaged on a
        // real-device APK they triple its size (~170MB native across four
        // ABIs vs ~55MB on the two ARM ABIs).
        // Every phone that can run this app is arm64 or armv7.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        debug {
            // Emulators are typically x86_64; allow running debug/androidTest on emulators
            ndk {
                abiFilters.add("x86_64")
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // Single distribution flavor (fork cut, docs/FEATURES.md): `foss` — our
    // own CameraX + OpenCV batch pipeline (app/src/foss), no Play services,
    // no ML Kit, no bundled Google modules. Everything else — library,
    // editing, exports, telemetry toggle — is shared main code. Local OCR is
    // stubbed out until the OCR phases land (server OCR, then PaddleOCR).
    flavorDimensions += "distribution"
    productFlavors {
        create("foss") {
            dimension = "distribution"
            versionNameSuffix = "-foss"
        }
    }

    // Release signing: CI provides the keystore via env vars (see the release
    // workflow). Without ANDROID_RELEASE_KEYSTORE_BASE64 set, release builds
    // stay unsigned so local developers can still assemble; they usually just
    // build debug.
    val releaseKeystoreB64 = System.getenv("ANDROID_RELEASE_KEYSTORE_BASE64")
    if (releaseKeystoreB64 != null) {
        val keystoreFile = File(buildDir, "release-keystore.jks")
        // writeBytes does not create parent directories; the foss-only
        // workflow's assembleFossRelease is the first gradle invocation on a
        // fresh CI runner, so app/build does not exist yet at configuration
        // time (it used to be created by the now-removed debug-APK step).
        keystoreFile.parentFile?.mkdirs()
        Base64.getDecoder().decode(releaseKeystoreB64).let { keystoreFile.writeBytes(it) }
        signingConfigs.create("release") {
            storeFile = keystoreFile
            storePassword = System.getenv("ANDROID_RELEASE_STORE_PASSWORD")
            keyAlias = System.getenv("ANDROID_RELEASE_KEY_ALIAS")
            keyPassword = System.getenv("ANDROID_RELEASE_KEY_PASSWORD")
            // Sideloaded updates must install over every shipped release:
            // sign with v1 (JAR) AND v2 so the APK carries META-INF
            // signatures as well as the v2 block. AGP 8 computes the
            // effective v1 flag from `enableV1Signing` (defaulting v1 off
            // when minSdk >= 24, as here) and ignores the legacy
            // `isV1SigningEnabled` for APK signing — so the new flag is
            // the one that matters (verified against AGP 8.13.2
            // SigningConfigImpl bytecode). Same stable key, additive
            // schemes — existing installs update without an uninstall.
            enableV1Signing = true
            isV1SigningEnabled = true
            isV2SigningEnabled = true
        }
        buildTypes { getByName("release") { signingConfig = signingConfigs.getByName("release") } }
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
        buildConfig = true
    }
}

/** Gradle property first, then environment variable, then empty. */
fun telemetryConfigValue(name: String): String =
    (project.findProperty(name) as? String)
        ?: System.getenv(name)
        ?: ""

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.kotlinx.coroutines.android)

    // Capture lives in the foss flavor (CameraX own pipeline); everything
    // else stays in main. Quoted configuration names: flavor accessors
    // don't exist at script-compile time in the Kotlin DSL.
    "fossImplementation"(libs.androidx.camera.core)
    "fossImplementation"(libs.androidx.camera.camera2)
    "fossImplementation"(libs.androidx.camera.lifecycle)
    "fossImplementation"(libs.androidx.camera.view)
    implementation(libs.pdfbox.android)
    implementation(libs.opencv)
    implementation(libs.onnxruntime.android)

    testImplementation(libs.junit)
    // android.jar ships org.json as stubs that throw in local JVM tests; the
    // real artifact provides the same API for the release-check parser tests.
    testImplementation(libs.org.json)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.uiautomator)

    debugImplementation(libs.androidx.compose.ui.tooling)
}

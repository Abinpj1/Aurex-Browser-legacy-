import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing is read from keystore.properties (git-ignored) or from AUREX_* environment
// variables (used by CI). Nothing secret is stored in the repository. When no key is configured,
// builds fall back to Android's default debug key, so anyone can still build and run the app.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(prop: String, env: String): String? =
    (keystoreProps.getProperty(prop) ?: System.getenv(env))?.takeIf { it.isNotBlank() }

val aurexStoreFile = signingValue("storeFile", "AUREX_KEYSTORE_FILE")
val aurexStorePassword = signingValue("storePassword", "AUREX_STORE_PASSWORD")
val aurexKeyAlias = signingValue("keyAlias", "AUREX_KEY_ALIAS")
val aurexKeyPassword = signingValue("keyPassword", "AUREX_KEY_PASSWORD")
val hasAurexSigning = aurexStoreFile != null && aurexStorePassword != null &&
    aurexKeyAlias != null && aurexKeyPassword != null

android {
    namespace = "com.aurex.browser"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.aurex.browser"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "1.1.0"
        // The bundled downloader engine (Python + yt-dlp + ffmpeg) is large per ABI.
        // These cover virtually every phone plus the emulator.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    // Use one fixed signing key for every official build (v1 + v2 + v3). An APK that is unsigned, or
    // signed with v1 only, is rejected as "package invalid" on Android 11+, and the in-app updater only
    // works when new builds are signed with the same key as the installed app.
    signingConfigs {
        if (hasAurexSigning) {
            create("aurex") {
                storeFile = rootProject.file(aurexStoreFile!!)
                storePassword = aurexStorePassword
                keyAlias = aurexKeyAlias
                keyPassword = aurexKeyPassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }
    val buildSigning = if (hasAurexSigning) signingConfigs.getByName("aurex") else signingConfigs.getByName("debug")
    buildTypes {
        debug {
            signingConfig = buildSigning
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = buildSigning
        }
    }
    // A lint finding must never stop the APK from being produced.
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // The engine runs bundled executables from the native lib dir, so the libs must be
        // extracted to disk instead of being mmapped from the APK.
        jniLibs { useLegacyPackaging = true }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.webkit:webkit:1.10.0")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.3.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")
    implementation("androidx.media3:media3-session:1.3.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Universal downloader engine (yt-dlp + Python + ffmpeg), used internally.
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // App language: on-device translation of the menus and messages (language packs are downloaded once)
    implementation("com.google.mlkit:translate:17.0.3")
}

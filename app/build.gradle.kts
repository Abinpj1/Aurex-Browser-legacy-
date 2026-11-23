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
        versionCode = 5
        versionName = "2.1.0"
        // Strip ~70 unused library locales (Aurex translates all 36 app languages on-device via MLKit).
        resourceConfigurations += listOf("en")
        // Single universal APK covering all real Android phones (64-bit arm64-v8a + 32-bit armeabi-v7a), zero x86 emulator bloat.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
        manifestPlaceholders["appLabel"] = "Aurex Browser"
    }

    // Disabled ABI splits: produces one single universal aurex-browser.apk for GitHub Releases & Orion Store.
    splits {
        abi {
            isEnable = false
        }
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
            applicationIdSuffix = ".debug"
            signingConfig = buildSigning
            manifestPlaceholders["appLabel"] = "aurex debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = buildSigning
        }
    }
    
    // Output a single fixed-name release APK: aurex-browser.apk
    applicationVariants.all {
        if (buildType.name == "release") {
            outputs.all {
                val outputImpl = this as com.android.build.gradle.internal.api.ApkVariantOutputImpl
                outputImpl.outputFileName = "aurex-browser.apk"
            }
        }
    }

    // A lint finding must never stop the APK from being produced.
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
    buildFeatures {
        compose = true
        buildConfig = false
        resValues = false
        aidl = false
        renderScript = false
        shaders = false
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // Omit Google Play encrypted dependency signing block from APK
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/LICENSE*",
            "/META-INF/NOTICE*",
            "/META-INF/DEPENDENCIES",
            "/META-INF/INDEX.LIST",
            "/META-INF/CHANGES",
            "/META-INF/README*",
            "/META-INF/*.version",
            "/META-INF/*.kotlin_module",
            "/META-INF/*.DSA",
            "/META-INF/*.RSA",
            "/META-INF/*.SF",
            "/META-INF/versions/**",
            "/META-INF/maven/**",
            "/META-INF/proguard/**",
            "/META-INF/com.android.tools/**",
            "/META-INF/com/android/build/gradle/**",
            "DebugProbesKt.bin",
            "kotlin-tooling-metadata.json",
            "jsr305_annotations/**",
            "error_prone/**",
            "third_party/**",
            "google/protobuf/**",
            "**/*.proto",
            "play-services-*.properties",
            "firebase-*.properties",
            "transport-*.properties",
            "okhttp3/internal/publicsuffix/NOTICE",
            "META-INF/*.txt",
            "META-INF/*.md",
            "META-INF/proguard/*",
            "META-INF/services/javax.*",
            "vision-*.properties",
            "ads-*.properties",
            "common.properties",
            "translate.properties",
            "language-id*.properties",
            "kotlin/**/*.kotlin_builtins",
            "kotlin/**/*.kotlin_metadata"
        )
        // The engine runs bundled executables from the native lib dir, so the libs must be
        // extracted to disk instead of being mmapped from the APK.
        jniLibs {
            useLegacyPackaging = true
            excludes += setOf("**/x86/**", "**/x86_64/**")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            "-Xno-param-assertions",
            "-Xno-call-assertions",
            "-Xno-receiver-assertions"
        )
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.webkit:webkit:1.10.0")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.3.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Universal downloader engine (yt-dlp + Python + ffmpeg), used internally.
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // App language: on-device translation of the menus and messages (language packs are downloaded once)
    implementation("com.google.mlkit:translate:17.0.3")
}

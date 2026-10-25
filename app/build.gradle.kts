import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.lightroom.mobile"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.lightroom.mobile"
        minSdk = 26
        targetSdk = 36
        versionCode = 20
        versionName = "0.20.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release signing secrets come from local.properties (git-ignored) or environment variables, never from git:
    //   lumaroom.storeFile, lumaroom.storePassword, lumaroom.keyAlias, lumaroom.keyPassword
    //   (env: LUMAROOM_STORE_FILE, LUMAROOM_STORE_PASSWORD, LUMAROOM_KEY_ALIAS, LUMAROOM_KEY_PASSWORD)
    val localProperties = Properties().apply { rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) } }
    fun secret(key: String): String? = localProperties.getProperty("lumaroom.$key")
        ?: System.getenv("LUMAROOM_" + key.replace(Regex("([a-z])([A-Z])"), "$1_$2").uppercase())
    val hasSigning = secret("storePassword") != null && secret("keyPassword") != null
    signingConfigs {
        create("release") {
            storeFile = rootProject.file(secret("storeFile") ?: "lumaroom-release.keystore")
            storePassword = secret("storePassword")
            keyAlias = secret("keyAlias") ?: "lumaroom"
            keyPassword = secret("keyPassword")
        }
    }

    buildTypes {
        release {
            // R8 strips unused code (the debug APK carries ~28 MB of dex) and resources.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Without secrets the release APK is built unsigned (sign it later) instead of failing.
            signingConfig = if (hasSigning) signingConfigs.getByName("release") else null
            // Ship only the architecture real phones use; keeps the APK small with no feature loss.
            ndk { abiFilters += "arm64-v8a" }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }

    // Stored uncompressed so models can be memory-mapped straight from the APK.
    androidResources { noCompress += "tflite" }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    // AndroidX ExifInterface: the platform android.media.ExifInterface has had malformed-EXIF CVEs.
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    // LiteRT (formerly TensorFlow Lite) runs the on-device AI models: people segmentation (bundled in
    // assets/models) and LaMa inpainting for Generative Remove (downloaded on first use).
    implementation("com.google.ai.edge.litert:litert:1.0.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    // Real org.json so JVM unit tests can exercise recipe serialization (the Android stub throws when un-mocked).
    testImplementation("org.json:json:20231013")
    // On-device feature tests (Bitmap pipeline, decoders, catalog): ./gradlew connectedDebugAndroidTest
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

plugins {
    id("com.android.application")
    // The Kotlin *language* plugin is deliberately absent — AGP 9.x compiles
    // Kotlin itself (see the root build file). Only the Compose compiler
    // plugin is applied, and its version must match the Kotlin version.
    id("org.jetbrains.kotlin.plugin.compose")
}

val origonSdkVersion = providers.gradleProperty("origonSdkVersion").getOrElse("0.3.0")

android {
    namespace = "origon.example.android"
    // compileSdk 37 / targetSdk 36 — the pairing `apps/android` already
    // ships. compileSdk only selects which APIs compile; targetSdk is the
    // runtime-behaviour opt-in, and 36 is Play's floor.
    compileSdk = 37
    // Match the SDK artifact build. Without an explicit pin this independent
    // example project lets AGP select a different/default NDK and its strip
    // tasks retain prebuilt native libraries behind an "Unable to strip"
    // warning.
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "origon.example.android"
        // Matches the SDK's minSdk (Android 6.0).
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        // The whole UI is Compose. viewBinding left with the last layout XML.
        compose = true
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // `java.time` is API 26 natively and this app is minSdk 23; the
        // transcript's timestamp formatting and the sidebar's day bucketing
        // need it desugared. The alternative — a hand-rolled ISO-8601 parser
        // — is strictly worse than the platform's.
        isCoreLibraryDesugaringEnabled = true
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/kotlin")
        }
    }
}

dependencies {
    implementation("ai.origon:sdk:$origonSdkVersion")
    // ClientConfig exposes a kotlinx JsonObject in its public API but the
    // SDK declares the dependency as `implementation`, so consumers must
    // add it explicitly to satisfy the compiler.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    implementation("androidx.core:core-ktx:1.13.1")
    // Kept for the XML app theme alone (`Theme.Material3.DayNight.NoActionBar`
    // in res/values/themes.xml), which paints the window before Compose takes
    // over. No Material *View* is used anywhere.
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Compose. The BOM governs every compose artifact, so those carry no
    // version of their own.
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")

    // Attachment thumbnails and the full-screen image preview. coil3 (not
    // coil 2) is the Compose-first line. 3.5.0 is the release that raised
    // coil's floor to minSdk 23 — this app sits exactly on it with zero
    // headroom, so a coil bump that moves that floor to 24 locks us out.
    implementation("io.coil-kt.coil3:coil-compose:3.5.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.5.0")
    // The PDF preview streams the attachment to a cache file itself, so it
    // needs the HTTP client directly and not only through coil.
    implementation("com.squareup.okhttp3:okhttp:5.4.0")

    implementation("org.jsoup:jsoup:1.23.1")
    implementation("org.commonmark:commonmark:0.30.0")
    implementation("org.commonmark:commonmark-ext-gfm-strikethrough:0.30.0")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
    constraints {
        add(
            "coreLibraryDesugaring",
            "com.android.tools:desugar_jdk_libs_configuration_nio:2.1.5",
        ) {
            version { strictly("2.1.5") }
            because("the reviewed NIO desugar runtime has one exact configuration companion")
        }
    }

    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.4.10")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
}

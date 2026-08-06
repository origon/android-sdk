plugins {
    id("com.android.application")
    // The Kotlin *language* plugin is deliberately absent — AGP 9.x compiles
    // Kotlin itself (see the root build file). Only the Compose compiler
    // plugin is applied, and its version must match the Kotlin version.
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "origon.example.android"
    // compileSdk 37 / targetSdk 36 — the pairing `apps/android` already
    // ships. compileSdk only selects which APIs compile; targetSdk is the
    // runtime-behaviour opt-in, and 36 is Play's floor.
    compileSdk = 37

    defaultConfig {
        applicationId = "origon.example.android"
        // Matches the SDK's minSdk (Android 6.0).
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        // Compose is ENABLED but not yet used — the screens are still Views.
        // Enabling it in isolation is deliberate: it proves the toolchain
        // move on unchanged UI code before any screen migrates.
        compose = true
        viewBinding = true
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
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/kotlin")
        }
    }
}

dependencies {
    implementation("ai.origon:sdk:0.2.0")
    // ClientConfig exposes a kotlinx JsonObject in its public API but the
    // SDK declares the dependency as `implementation`, so consumers must
    // add it explicitly to satisfy the compiler.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.cardview:cardview:1.0.0")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("io.coil-kt:coil:2.7.0")

    // Compose — enabled now, consumed when the screens migrate. The BOM
    // governs every compose artifact, so those carry no version of their own.
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
}

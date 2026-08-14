import com.vanniktech.maven.publish.SonatypeHost

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.vanniktech.maven.publish") version "0.30.0"
}

android {
    namespace = "ai.origon.sdk"
    compileSdk = 35
    // AGP otherwise selects its own default NDK and may retain the prebuilt
    // Rust libraries behind an "Unable to strip" warning. This is the same
    // toolchain that builds and validates the three native inputs.
    ndkVersion = "27.2.12479018"

    defaultConfig {
        // API 23 (Android 6.0). The native audio backend uses Oboe, which
        // selects AAudio on API 27+ and OpenSL ES on 23-26 at runtime, and the
        // device monitor uses AudioManager enumeration/change-callback APIs that
        // are available from API 23.
        minSdk = 23
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    testImplementation(kotlin("test"))
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}

mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL, automaticRelease = true)
    signAllPublications()

    coordinates(
        groupId = "ai.origon",
        artifactId = "sdk",
        version = providers.gradleProperty("sdkVersion").getOrElse("0.0.0-LOCAL"),
    )

    pom {
        name.set("Origon Android SDK")
        description.set("Android SDK for the Origon platform")
        url.set("https://origon.ai")
        licenses {
            license {
                name.set("Origon Commercial License")
                url.set("https://github.com/Origon/android-sdk/blob/main/LICENSE")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("origon")
                name.set("Origon")
                url.set("https://origon.ai")
            }
        }
        scm {
            url.set("https://github.com/Origon/android-sdk")
            connection.set("scm:git:git://github.com/Origon/android-sdk.git")
            developerConnection.set("scm:git:ssh://git@github.com/Origon/android-sdk.git")
        }
    }
}

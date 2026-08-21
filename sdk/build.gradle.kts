import com.vanniktech.maven.publish.SonatypeHost
import java.security.MessageDigest
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication

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

// Exact-artifact release mode. Normal local and Central publication keep using
// AGP's release component. A release operator opts into this mode only after a
// candidate AAR has been built, inspected, and hashed: the unclassified AAR
// artifact is replaced with that exact file while sources/javadoc artifacts and
// the generated POM remain owned by the normal publication. Because the custom
// file has no build dependency, Gradle cannot rebuild it during upload.
val exactAarPath = providers.gradleProperty("exactAarPath")
val exactAarSha256 = providers.gradleProperty("exactAarSha256")
require(exactAarPath.isPresent == exactAarSha256.isPresent) {
    "exactAarPath and exactAarSha256 must be supplied together"
}

if (exactAarPath.isPresent) {
    require(providers.gradleProperty("sdkVersion").isPresent) {
        "exact AAR mode requires an explicit sdkVersion"
    }
    val exactAar = file(exactAarPath.get()).canonicalFile
    val expectedSha256 = exactAarSha256.get().lowercase()
    require(exactAar.isFile) { "exact release AAR does not exist: $exactAar" }
    require(expectedSha256.matches(Regex("[0-9a-f]{64}"))) {
        "exactAarSha256 must be one lowercase SHA-256"
    }

    gradle.projectsEvaluated {
        val publications = extensions
            .getByType(PublishingExtension::class.java)
            .publications
            .withType(MavenPublication::class.java)
        publications.configureEach {
            require(groupId == "ai.origon" && artifactId == "sdk") {
                "exact AAR mode refuses unexpected coordinates: $groupId:$artifactId:$version"
            }
            artifacts.removeAll { artifact ->
                artifact.extension == "aar" && artifact.classifier.isNullOrBlank()
            }
            artifact(exactAar) {
                extension = "aar"
            }
        }

        val verifyExactAarPublication = tasks.register("verifyExactAarPublication") {
            group = "publishing"
            description = "Verifies the exact prebuilt AAR and no-rebuild publication wiring."
            inputs.file(exactAar)
            doLast {
                check(publications.isNotEmpty()) { "no Maven publication was configured" }
                val digest = MessageDigest.getInstance("SHA-256")
                exactAar.inputStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
                val actual = digest.digest().joinToString("") {
                    "%02x".format(it.toInt() and 0xff)
                }
                check(actual == expectedSha256) {
                    "exact AAR SHA-256 changed: expected $expectedSha256, found $actual"
                }
                publications.forEach { publication ->
                    check(publication.groupId == "ai.origon")
                    check(publication.artifactId == "sdk")
                    check(publication.version == providers.gradleProperty("sdkVersion").get())
                    val mainAars = publication.artifacts.filter { artifact ->
                        artifact.extension == "aar" && artifact.classifier.isNullOrBlank()
                    }
                    check(mainAars.size == 1 && mainAars.single().file.canonicalFile == exactAar) {
                        "publication is not bound to the exact AAR: $mainAars"
                    }
                    check(
                        mainAars.single().buildDependencies
                            .getDependencies(this)
                            .isEmpty()
                    ) {
                        "exact AAR unexpectedly has build dependencies"
                    }
                }
                println(
                    "verified exact publication ai.origon:sdk:${providers.gradleProperty("sdkVersion").get()} " +
                        "aar=$actual no-rebuild=true"
                )
            }
        }

        tasks.matching { task ->
            task.name.contains("publish", ignoreCase = true) ||
                task.name.contains("MavenCentral", ignoreCase = true)
        }.configureEach {
            dependsOn(verifyExactAarPublication)
        }
    }
}

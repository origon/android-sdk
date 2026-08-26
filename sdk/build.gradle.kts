import com.vanniktech.maven.publish.SonatypeHost
import java.security.MessageDigest
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

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
// candidate AAR has been built, inspected, and hashed. It creates a separate
// publication with the frozen AAR rather than mutating the AGP-backed one:
// publication tasks for `exact` therefore cannot retain a hidden dependency on
// bundleReleaseAar/native merge/strip through Gradle module metadata.
val exactAarPath = providers.gradleProperty("exactAarPath")
val exactAarSha256 = providers.gradleProperty("exactAarSha256")
val exactSourcesJarPath = providers.gradleProperty("exactSourcesJarPath")
val exactSourcesJarSha256 = providers.gradleProperty("exactSourcesJarSha256")
val exactJavadocJarPath = providers.gradleProperty("exactJavadocJarPath")
val exactJavadocJarSha256 = providers.gradleProperty("exactJavadocJarSha256")
val exactPomDependencies = listOf(
    listOf("org.jetbrains.kotlin", "kotlin-stdlib", "2.1.0", "compile"),
    listOf("org.jetbrains.kotlinx", "kotlinx-serialization-json", "1.7.3", "runtime"),
    listOf("org.jetbrains.kotlinx", "kotlinx-coroutines-core", "1.8.1", "runtime"),
)
val exactInputs = listOf(
    exactAarPath,
    exactAarSha256,
    exactSourcesJarPath,
    exactSourcesJarSha256,
    exactJavadocJarPath,
    exactJavadocJarSha256,
)
require(exactInputs.all { it.isPresent } || exactInputs.none { it.isPresent }) {
    "exact AAR, sources JAR, javadoc JAR, and all three SHA-256 values must be supplied together"
}

if (exactAarPath.isPresent) {
    require(providers.gradleProperty("sdkVersion").isPresent) {
        "exact AAR mode requires an explicit sdkVersion"
    }
    val exactAar = file(exactAarPath.get()).canonicalFile
    val exactSourcesJar = file(exactSourcesJarPath.get()).canonicalFile
    val exactJavadocJar = file(exactJavadocJarPath.get()).canonicalFile
    val expectedAarSha256 = exactAarSha256.get().lowercase()
    val expectedSourcesSha256 = exactSourcesJarSha256.get().lowercase()
    val expectedJavadocSha256 = exactJavadocJarSha256.get().lowercase()
    require(exactAar.isFile) { "exact release AAR does not exist: $exactAar" }
    require(exactSourcesJar.isFile) { "exact sources JAR does not exist: $exactSourcesJar" }
    require(exactJavadocJar.isFile) { "exact javadoc JAR does not exist: $exactJavadocJar" }
    require(
        listOf(expectedAarSha256, expectedSourcesSha256, expectedJavadocSha256)
            .all { it.matches(Regex("[0-9a-f]{64}")) }
    ) {
        "exact artifact SHA-256 values must be lowercase 64-character digests"
    }

    gradle.projectsEvaluated {
        val publishing = project.extensions.getByType(PublishingExtension::class.java)
        val publications = publishing.publications.withType(MavenPublication::class.java)
        val normalPublication = publications.named("maven").get()
        require(normalPublication.groupId == "ai.origon" && normalPublication.artifactId == "sdk") {
            "exact AAR mode refuses unexpected coordinates: " +
                "${normalPublication.groupId}:${normalPublication.artifactId}:${normalPublication.version}"
        }
        val exactPublication = publishing.publications.create<MavenPublication>("exact") {
            groupId = normalPublication.groupId
            artifactId = normalPublication.artifactId
            version = providers.gradleProperty("sdkVersion").get()
            artifact(exactAar) {
                extension = "aar"
            }
            artifact(exactSourcesJar) {
                extension = "jar"
                classifier = "sources"
            }
            artifact(exactJavadocJar) {
                extension = "jar"
                classifier = "javadoc"
            }
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
                withXml {
                    val dependenciesNode = asNode().appendNode("dependencies")
                    exactPomDependencies.forEach { dependency ->
                        val dependencyNode = dependenciesNode.appendNode("dependency")
                        dependencyNode.appendNode("groupId", dependency[0])
                        dependencyNode.appendNode("artifactId", dependency[1])
                        dependencyNode.appendNode("version", dependency[2])
                        dependencyNode.appendNode("scope", dependency[3])
                    }
                }
            }
        }

        val verifyExactAarPublication = project.tasks.register("verifyExactAarPublication") {
            group = "publishing"
            description = "Verifies the exact prebuilt publication and no-rebuild wiring."
            inputs.files(exactAar, exactSourcesJar, exactJavadocJar)
            dependsOn(project.tasks.named("generatePomFileForExactPublication"))
            doLast {
                fun sha256(inputFile: java.io.File): String {
                    val digest = MessageDigest.getInstance("SHA-256")
                    inputFile.inputStream().use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            digest.update(buffer, 0, count)
                        }
                    }
                    return digest.digest().joinToString("") {
                        "%02x".format(it.toInt() and 0xff)
                    }
                }
                val actualAarSha256 = sha256(exactAar)
                val actualSourcesSha256 = sha256(exactSourcesJar)
                val actualJavadocSha256 = sha256(exactJavadocJar)
                check(actualAarSha256 == expectedAarSha256) {
                    "exact AAR SHA-256 changed: expected $expectedAarSha256, found $actualAarSha256"
                }
                check(actualSourcesSha256 == expectedSourcesSha256) {
                    "exact sources SHA-256 changed: expected $expectedSourcesSha256, found $actualSourcesSha256"
                }
                check(actualJavadocSha256 == expectedJavadocSha256) {
                    "exact javadoc SHA-256 changed: expected $expectedJavadocSha256, found $actualJavadocSha256"
                }
                check(exactPublication.groupId == "ai.origon")
                check(exactPublication.artifactId == "sdk")
                check(exactPublication.version == providers.gradleProperty("sdkVersion").get())
                val mainAars = exactPublication.artifacts.filter { artifact ->
                    artifact.extension == "aar" && artifact.classifier.isNullOrBlank()
                }
                check(mainAars.size == 1 && mainAars.single().file.canonicalFile == exactAar) {
                    "exact publication is not bound to the frozen AAR: $mainAars"
                }
                check(mainAars.single().buildDependencies.getDependencies(this).isEmpty()) {
                    "exact AAR unexpectedly has build dependencies"
                }
                val sourcesArtifacts = exactPublication.artifacts.filter { artifact ->
                    artifact.extension == "jar" && artifact.classifier == "sources"
                }
                check(
                    sourcesArtifacts.size == 1 &&
                        sourcesArtifacts.single().file.canonicalFile == exactSourcesJar &&
                        sourcesArtifacts.single().buildDependencies.getDependencies(this).isEmpty()
                ) {
                    "exact sources binding changed: $sourcesArtifacts"
                }
                val javadocArtifacts = exactPublication.artifacts.filter { artifact ->
                    artifact.extension == "jar" && artifact.classifier == "javadoc"
                }
                check(
                    javadocArtifacts.size == 1 &&
                        javadocArtifacts.single().file.canonicalFile == exactJavadocJar &&
                        javadocArtifacts.single().buildDependencies.getDependencies(this).isEmpty()
                ) {
                    "exact javadoc binding changed: $javadocArtifacts"
                }
                check(exactPublication.artifacts.size == 3) {
                    "exact publication contains unexpected artifacts: ${exactPublication.artifacts}"
                }
                val pomFile = layout.buildDirectory
                    .file("publications/exact/pom-default.xml")
                    .get()
                    .asFile
                check(pomFile.isFile) { "exact publication POM was not generated: $pomFile" }
                val documentFactory = DocumentBuilderFactory.newInstance().apply {
                    setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                    isXIncludeAware = false
                    isExpandEntityReferences = false
                }
                val document = documentFactory.newDocumentBuilder().parse(pomFile)
                val actualPomDependencies = buildSet {
                    val nodes = document.getElementsByTagName("dependency")
                    for (index in 0 until nodes.length) {
                        val element = nodes.item(index) as Element
                        fun textOf(tagName: String): String =
                            element.getElementsByTagName(tagName).item(0).textContent
                        add(
                            listOf(
                                textOf("groupId"),
                                textOf("artifactId"),
                                textOf("version"),
                                textOf("scope"),
                            )
                        )
                    }
                }
                check(actualPomDependencies == exactPomDependencies.toSet()) {
                    "exact POM dependencies changed: expected $exactPomDependencies, " +
                        "found $actualPomDependencies"
                }
                println(
                    "verified exact publication ai.origon:sdk:${providers.gradleProperty("sdkVersion").get()} " +
                        "aar=$actualAarSha256 sources=$actualSourcesSha256 " +
                        "javadoc=$actualJavadocSha256 no-rebuild=true"
                )
            }
        }

        project.tasks.matching { task ->
            task.name.startsWith("publishExactPublication", ignoreCase = true) ||
                task.name == "createStagingRepository" ||
                task.name == "releaseRepository"
        }.configureEach {
            dependsOn(verifyExactAarPublication)
        }

        project.tasks.named("releaseRepository").configure {
            mustRunAfter(project.tasks.named("publishExactPublicationToMavenCentralRepository"))
        }
    }
}

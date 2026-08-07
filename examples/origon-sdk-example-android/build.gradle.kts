// Raise the Kotlin version used by AGP's **built-in Kotlin** support.
//
// AGP 9.x compiles Kotlin itself (`android.builtInKotlin` defaults to true
// since AGP 9.0) and pins its own KGP in its POM. Applying
// `org.jetbrains.kotlin.android` on top is NOT an alternative — it fails the
// build outright with "Cannot add extension with name 'kotlin', as there is
// an extension already registered with that name". The documented route to a
// newer Kotlin is this classpath bump; Gradle's newest-wins resolution then
// beats AGP's bundled version.
//
// The Compose compiler is a Kotlin-versioned plugin, so the two move together
// — which is why enabling Compose drags a Kotlin bump rather than being a
// dependency-only change.
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.10")
    }
}

plugins {
    id("com.android.application") version "9.3.1" apply false
    // Version must match the Kotlin version above — a Compose compiler
    // mismatched against the compiler is a hard failure, not a warning.
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
}

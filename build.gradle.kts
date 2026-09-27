// Root build file. Only the Kotlin/JVM plugin is declared here: the Android plugin aliases live in
// the Android modules, so a machine with no Android SDK (where settings.gradle.kts skips those
// modules) never resolves AGP.
plugins {
    alias(libs.plugins.jetbrains.kotlin.jvm) apply false
}

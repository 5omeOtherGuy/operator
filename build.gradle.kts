// Root build file. Every plugin the subprojects use is declared here with `apply false`, so Gradle
// resolves each plugin id once and no module hits "the plugin is already on the classpath with an
// unknown version". Nothing is applied at the root: :agent-core is the only module that configures on
// a JDK-only machine, because settings.gradle.kts skips the Android modules when no SDK is present.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.jetbrains.kotlin.android) apply false
    alias(libs.plugins.jetbrains.kotlin.jvm) apply false
}

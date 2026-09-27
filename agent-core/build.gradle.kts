// :agent-core is pure Kotlin/JVM (FOUNDATION §2.2): no Android plugin, no Android dependency.
// It is unit-tested locally and in the CI `jvm` job with `./gradlew :agent-core:test`.
plugins {
    alias(libs.plugins.jetbrains.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.reflect)
}

tasks.withType<Test>().configureEach {
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

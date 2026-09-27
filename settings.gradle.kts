pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "operator"

// :agent-core is pure Kotlin/JVM (FOUNDATION §2.2) and the only module that builds on a machine with
// just a JDK 17: `./gradlew :agent-core:test` must work there (docs/m1/PLAN.md).
include(":agent-core")

// The Android modules need an SDK to configure (compileSdk 36): :app, :llm-api, :llm, :llm-stub,
// :fixture. They are included only when one is present, so the JDK-only path above stays usable.
// The scaffold's `:llama` include is gone: :llm compiles the upstream lib sources in place from the
// pinned submodule instead (FOUNDATION §2.2, §4.2 C2, ADR-0005).
val operatorAndroidModules = listOf(":app", ":llm-api", ":llm", ":llm-stub", ":fixture")

val sdkDirFromLocalProperties: String? = file("local.properties")
    .takeIf { it.isFile }
    ?.let { localProperties ->
        java.util.Properties().apply { localProperties.inputStream().use { load(it) } }.getProperty("sdk.dir")
    }

val androidSdkDir: String? =
    (listOf("ANDROID_HOME", "ANDROID_SDK_ROOT").mapNotNull { System.getenv(it) } + listOfNotNull(sdkDirFromLocalProperties))
        .firstOrNull { it.isNotBlank() && file(it).isDirectory }

if (androidSdkDir != null) {
    operatorAndroidModules.forEach { include(it) }
    println("operator: Android SDK at $androidSdkDir -> including ${operatorAndroidModules.joinToString(", ")}")
} else {
    println("operator: no Android SDK (ANDROID_HOME / ANDROID_SDK_ROOT / sdk.dir) -> only :agent-core is configured")
}

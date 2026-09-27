// :llm-stub is the emulator channel's inference service (FOUNDATION §4.2 R25, §12): a Kotlin-only
// Android library with no externalNativeBuild and no .so of any kind, so
// `./gradlew :app:assembleEmulatorStubRelease` cannot package native code even without a -P flag.
// S1 fills it with the scripted fixtures and the emulator tests drive :app against it.
// Same service class name and same ":llm" process as the real :llm so :app's manifest, the AIDL
// surface and the loop are identical on both channels.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.jetbrains.kotlin.android)
}

android {
    // Distinct from :llm-api's (dev.operator.llm.api) and :llm's (dev.operator.llm.runtime)
    // namespaces: two modules with the same namespace would both generate dev.operator.llm.R and the
    // APK would fail to merge them.
    namespace = "dev.operator.llm.stub"
    compileSdk = 36

    defaultConfig {
        minSdk = 33
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":llm-api"))
}

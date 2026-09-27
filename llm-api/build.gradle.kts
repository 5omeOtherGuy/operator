// :llm-api is the AIDL + Parcelable surface between the main process and :llm (FOUNDATION §2.3,
// ADR-0002). No client logic lives here; the suspend client wrappers of §2.2 come with S1.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.jetbrains.kotlin.android)
}

android {
    // The Kotlin Parcelables and the AIDL interfaces live in package dev.operator.llm; the namespace
    // only names this module's R/BuildConfig class and must differ from :llm's (duplicate namespaces
    // would produce duplicate R classes in the APK).
    namespace = "dev.operator.llm.api"
    compileSdk = 36

    defaultConfig {
        minSdk = 33
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        aidl = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
}

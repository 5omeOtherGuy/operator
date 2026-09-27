// :fixture is the only module that may hold android.permission.INTERNET (FOUNDATION §2.1 table,
// §4.8, C12; ADR-0015): it serves the localhost test pages, posts fixture notifications and is the T3
// install target, so that no operator variant needs network access. S12 fills it.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
}

android {
    namespace = "dev.operator.fixture"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.operator.fixture"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-M1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

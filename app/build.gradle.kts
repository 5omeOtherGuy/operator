// :app is dev.operator (FOUNDATION §2.1, §2.2): UI, AgentService (FGS), the loop's host, Executor,
// Gate, adapters, NotificationListener, Keeper and the audit log. No native code in this process
// (ADR-0002); the model lives in :llm (arm64 channels) or in the Kotlin-only :llm-stub (emulator).
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
}

android {
    namespace = "dev.operator"
    compileSdk = 36
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        applicationId = "dev.operator"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-M1"
    }

    // §12 D10 and research 07 R1: three channels out of one applicationId and one signing key.
    //   dev          - the eval channel: testOnly and debuggable (declared in src/dev), arm64-v8a,
    //                  native :llm, ships EvalReceiver.
    //   prod         - the future non-testOnly release: arm64-v8a, native :llm, no eval surface.
    //   emulatorStub - x86_64 with the Kotlin-only :llm-stub instead of :llm, so the APK cannot
    //                  carry native code even when no -P flag is passed (§4.2 R25).
    flavorDimensions += "channel"
    productFlavors {
        create("dev") {
            dimension = "channel"
            ndk { abiFilters += "arm64-v8a" }
        }
        create("prod") {
            dimension = "channel"
            ndk { abiFilters += "arm64-v8a" }
        }
        create("emulatorStub") {
            dimension = "channel"
            ndk { abiFilters += "x86_64" }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // ADR-0014's release key comes later. Until then dev, prod and emulatorStub are all
            // signed with the debug key, so they install over each other (§12 D2).
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // §4.2 R11: native libraries are extracted at install, so that ggml's runtime backend loader
    // finds the CPU variants in nativeLibraryDir.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
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

dependencies {
    implementation(project(":agent-core"))
    implementation(project(":llm-api"))

    // Exactly one inference service per channel, and the emulator channel never sees :llm, so its
    // APK has no native code by construction (FOUNDATION §4.2 R25, §12 D10).
    "devImplementation"(project(":llm"))
    "prodImplementation"(project(":llm"))
    "emulatorStubImplementation"(project(":llm-stub"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.biometric)
    implementation(libs.kotlinx.coroutines.android)
}

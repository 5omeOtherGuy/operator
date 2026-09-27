// :app is dev.operator (FOUNDATION §2.1, §2.2): UI, AgentService (FGS), the loop's host, Executor,
// Gate, adapters, NotificationListener, Keeper and the audit log. No native code in this process
// (ADR-0002); the model lives in :llm.
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

    // §4.2 R25: the shipped variant is arm64-v8a with the native :llm; the emulator variant is
    // x86_64 and is always built with -Poperator.noNative=true, so its APK carries no .so.
    flavorDimensions += "channel"
    productFlavors {
        create("dev") {
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
            // ADR-0014's release key comes later. Until then every variant is signed with the debug
            // key, so the dev APK and the release APK install over each other (§12 D2).
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
    implementation(project(":llm"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.biometric)
    implementation(libs.kotlinx.coroutines.android)
}

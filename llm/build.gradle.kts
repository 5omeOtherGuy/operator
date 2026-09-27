// :llm hosts LlmService in its own process and the native llama.cpp build (FOUNDATION §2.1, §2.2,
// §4.2; ADR-0002, ADR-0005). The upstream examples/llama.android/lib Kotlin sources are compiled in
// place from the pinned submodule; the CMakeLists is copied into llm/src/main/cpp with the operator
// patch set (P1 + the §4.2 flags) and operator_jni.cpp is added to the target.
// Only the arm64-v8a channels (:app dev and prod) depend on this module; the emulator channel uses
// the Kotlin-only :llm-stub, so no variant of :app can pull x86_64 native code in (§4.2 R25).
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.jetbrains.kotlin.android)
}

val llamaCppDir = rootProject.layout.projectDirectory.dir("third_party/llama.cpp")
val upstreamLibDir = llamaCppDir.dir("examples/llama.android/lib")

// -Poperator.noNative=true skips externalNativeBuild entirely: the fast `kotlin` CI job compiles the
// whole variant matrix without an NDK (§4.2 R25, ADR-0005). It is not what keeps the emulator
// channel native-free — that channel does not depend on this module at all.
val noNative = providers.gradleProperty("operator.noNative").orNull == "true"

// -Poperator.ccache=true wires the ccache launchers; the `apk` CI job installs ccache and caches it.
val useCcache = providers.gradleProperty("operator.ccache").orNull == "true"

android {
    // Distinct from :llm-api's namespace (dev.operator.llm.api) on purpose: two modules with the same
    // namespace would both generate dev.operator.llm.R and the APK would fail to merge them.
    namespace = "dev.operator.llm.runtime"
    compileSdk = 36
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        minSdk = 33

        // The native engine is arm64-v8a only (§4.2 R25): every consumer is the dev or the prod
        // channel of :app, both arm64-v8a, and this module has no flavour of its own so that no
        // x86_64 variant can ever match an :app variant.
        ndk { abiFilters += "arm64-v8a" }

        externalNativeBuild {
            cmake {
                arguments += "-DOPERATOR_LLAMA_SRC=${llamaCppDir.asFile.absolutePath}"
                if (useCcache) {
                    // The `apk` CI job installs ccache and caches $CCACHE_DIR (ADR-0015 lists ccache
                    // as considered; F0 measures it, the NDK build is the only consumer).
                    arguments += listOf(
                        "-DCMAKE_C_COMPILER_LAUNCHER=ccache",
                        "-DCMAKE_CXX_COMPILER_LAUNCHER=ccache",
                    )
                }
            }
        }
    }

    if (!noNative) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.31.6"
            }
        }
    }

    sourceSets.getByName("main").java.srcDir(upstreamLibDir.dir("src/main/java").asFile)

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
    implementation(libs.kotlinx.coroutines.android)

    // F0 placeholder: the `:llm` service that this module will implement is still the stub in
    // :llm-stub, so the dev and prod channels get a declared, bindable service while the native
    // engine is being written. S1 declares the real service in llm/src/main/AndroidManifest.xml and
    // drops this dependency.
    implementation(project(":llm-stub"))
}

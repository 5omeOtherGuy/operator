# operator

A local LLM that operates an Android phone: on-device model plus an accessibility-service app.

Status: prototype. Development is paused; `main` holds the M1 foundation (module skeleton, frozen core API, CI), and nothing is installable or usable yet.

The model runs on the device, and an app on the same device carries out its actions. Nothing leaves the phone.

## Design

- **Model:** PrismML Bonsai (1-bit / ternary GGUF) served by llama.cpp on the phone. Upstream llama.cpp supports Q1_0 and Q2_0. Start with Bonsai 8B (1.2 GB) and try 27B (3.9 GB) where RAM allows.
- **Hands:** an accessibility service in the operator app. It reads the screen as a node tree, performs taps, swipes, text entry and global actions (Back, Home, notifications), and takes screenshots. It stays enabled across reboots with no USB or wireless debugging.
- **Keeping it on:** a one-time `adb shell pm grant <app> android.permission.WRITE_SECURE_SETTINGS` lets the app re-enable its own accessibility service when the OEM switches it off. The grant survives reboots.
- **Direct APIs first:** alarms, timers, calendar, calls, SMS, media and app launching go through the ordinary Android intents and permissions. A notification listener reads and answers notifications. The accessibility UI path is the fallback for everything else.
- **Admin:** the operator app is the **device owner** (`dpm set-device-owner`). It installs and uninstalls APKs silently, grants runtime permissions, hides or suspends apps, and sets restrictions, reboots and update policy. Setting it requires no accounts and no secondary users on the device (Android 14+). Remove the accounts and the App Cloner user first, then add them back. Development builds are `android:testOnly="true"` so `adb shell dpm remove-active-admin` can undo it. `wipeData` is never exposed to the model.
- **Not used:** Shizuku (needs re-activation or Wi-Fi at boot) and root (keeps the bootloader unlocked, breaks Play Integrity, needs re-patching after every OTA).

## Decisions

- 2026-09-27: control stack = accessibility service + one-time `WRITE_SECURE_SETTINGS` grant + device owner. No Shizuku, no root.

## Safety

The model reads untrusted text: web pages, notifications, messages. Any action that can't be undone (send, pay, delete, install, change settings) needs confirmation on screen from the user. Text the model reads from the screen is data, never instructions.

## First milestone (M1)

M1 is defined in [`docs/design/FOUNDATION.md`](docs/design/FOUNDATION.md) §14: one `dev` APK, built only in GitHub Actions from a tagged commit and installed on the reference phone by the §10.1 runbook, passes every fixed exit criterion, and its numbers are stored in `metrics.json` under that tag. A stage 0 benchmark spike comes first (`llama-bench` on the phone and the unchanged upstream `llama.android` app); it replaces the earlier Termux `llama-server` plan, and the device owner moves to M2.

`main` contains the foundation: the Gradle modules `:agent-core`, `:app`, `:llm-api`, `:llm`, `:llm-stub` and `:fixture` with the M1 components stubbed, the frozen `:agent-core` API, the `:llm` AIDL surface and the CI workflow `.github/workflows/build.yml`. The slices that implement M1 are listed in [`docs/m1/PLAN.md`](docs/m1/PLAN.md); progress is tracked in the M1 epic, issue [#30](https://github.com/5omeOtherGuy/operator/issues/30).

Reference device: OnePlus 13 (SM8750, 16 GB RAM), OxygenOS 16.

## Build

`scripts/gate` runs every check a change must pass (secret scan, tests, NDK-free compile of every channel); it needs JDK 17, an Android SDK, the `third_party/llama.cpp` submodule and `gitleaks`, and is what the CI job `gate` runs. See [`AGENTS.md`](AGENTS.md) for the repository rules.

`./gradlew :agent-core:test` — the pure Kotlin/JVM module builds and tests with JDK 17 alone. The five Android modules (`:app`, `:llm-api`, `:llm`, `:llm-stub`, `:fixture`) are only included when an Android SDK is present (`ANDROID_HOME`, `ANDROID_SDK_ROOT`, or `sdk.dir` in `local.properties`), so this works on a JDK-only machine.

`:app` has three channels (§12): `dev` (arm64-v8a, native `:llm`, `testOnly` and debuggable, ships the `EvalReceiver` eval surface), `prod` (arm64-v8a, native `:llm`, no eval surface — the future non-testOnly release) and `emulatorStub` (x86_64, the Kotlin-only `:llm-stub`, no native code by construction). All three share the `dev.operator` application id and, until ADR-0014's key exists, the debug signing key.

APKs come from GitHub Actions: the `apk` job of `.github/workflows/build.yml` builds the `dev` (arm64-v8a, native `:llm`), the `emulatorStub` (x86_64, no native code) and the `fixture` variant and uploads them as the `operator-apks` artifact. The `checks` job prints the `.so` set of both operator APKs and fails when the dev APK carries no `libggml-cpu*.so` or when the stub APK carries any `.so`; it also fails when the dev channel's merged manifest is not `testOnly` or when the `prod` channel's manifest carries `EvalReceiver`, `APPROVE_GATE` or `testOnly`.

With an SDK and the pinned NDK `30.0.16248370` plus CMake `3.31.6` installed: `./gradlew :app:assembleDevRelease` builds the arm64-v8a dev APK with the native `:llm`, and `./gradlew :app:assembleEmulatorStubRelease` builds the native-free emulator APK with no flag at all. `-Poperator.noNative=true` skips `externalNativeBuild` entirely, which is what the fast `kotlin` CI job uses for the whole channel matrix.

## Licence

MIT, see [`LICENSE`](LICENSE). The llama.cpp submodule keeps its own MIT licence.

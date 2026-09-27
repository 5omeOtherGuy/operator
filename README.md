# operator

A local LLM that operates an Android phone. The model runs on the device, and an app on the same device carries out its actions. Nothing leaves the phone.

Status: planning. No code yet.

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

## First milestone

1. Termux: llama.cpp `llama-server` with Bonsai 8B on localhost. Measure tokens/s and check that tool calls are reliable.
2. A minimal app with an accessibility service exposing `read_screen`, `tap`, `type`, `swipe`, `back`, `home`, `open_app`.
3. An agent loop between them, with the confirmation gate.
4. Device owner: move the admin actions (install, uninstall, permissions) from the UI path to `DevicePolicyManager`.

Reference device: OnePlus 13 (SM8750, 16 GB RAM), OxygenOS 16.

## Build

`./gradlew :agent-core:test` — the pure Kotlin/JVM module builds and tests with JDK 17 alone. The four Android modules (`:app`, `:llm-api`, `:llm`, `:fixture`) are only included when an Android SDK is present (`ANDROID_HOME`, `ANDROID_SDK_ROOT`, or `sdk.dir` in `local.properties`), so this works on a JDK-only machine.

APKs come from GitHub Actions: the `apk` job of `.github/workflows/build.yml` builds the `dev` (arm64-v8a, native `:llm`), the `emulatorStub` (x86_64, no native code) and the `fixture` variant and uploads them as the `operator-apks` artifact. The `checks` job prints the `.so` set of both operator APKs and fails when the dev APK carries no `libggml-cpu*.so` or when the stub APK carries any `.so`.

With an SDK and the pinned NDK `29.0.13113456` plus CMake `3.31.6` installed: `./gradlew :app:assembleDevRelease` builds the arm64-v8a dev APK with the native `:llm`, and `./gradlew :app:assembleEmulatorStubRelease -Poperator.noNative=true` builds the stub flavour, which skips `externalNativeBuild` entirely.

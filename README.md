# operator

A local LLM that operates an Android phone. The model runs on the device, and an app on the same device carries out its actions. Nothing leaves the phone.

Status: planning. No code yet.

## Design

- **Model:** PrismML Bonsai (1-bit / ternary GGUF) served by llama.cpp on the phone. Upstream llama.cpp supports Q1_0 and Q2_0. Start with Bonsai 8B (1.2 GB) and try 27B (3.9 GB) where RAM allows.
- **Hands:** an accessibility service in the operator app. It reads the screen as a node tree, performs taps, swipes, text entry and global actions (Back, Home, notifications), and takes screenshots. It stays enabled across reboots with no USB or wireless debugging.
- **Keeping it on:** a one-time `adb shell pm grant <app> android.permission.WRITE_SECURE_SETTINGS` lets the app re-enable its own accessibility service when the OEM switches it off. The grant survives reboots.
- **Shell-level actions (optional):** Shizuku 13.6+ auto-starts without root on Android 13+ when the phone is on a trusted Wi-Fi network.
- **No root.** Root would keep the bootloader unlocked, break Play Integrity and need re-patching after every OTA.

## Safety

The model reads untrusted text: web pages, notifications, messages. Any action that can't be undone (send, pay, delete, install, change settings) needs confirmation on screen from the user. Text the model reads from the screen is data, never instructions.

## First milestone

1. Termux: llama.cpp `llama-server` with Bonsai 8B on localhost. Measure tokens/s and check that tool calls are reliable.
2. A minimal app with an accessibility service exposing `read_screen`, `tap`, `type`, `swipe`, `back`, `home`, `open_app`.
3. An agent loop between them, with the confirmation gate.

Reference device: OnePlus 13 (SM8750, 16 GB RAM), OxygenOS 16.

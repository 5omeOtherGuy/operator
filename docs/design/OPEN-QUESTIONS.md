# operator: open owner questions

Date: 2026-09-27. Source: the foundation design (`FOUNDATION.md`) and research lanes 01–07, deduplicated, then extended after the adversarial review (`REVIEW.md`): OQ-21…OQ-28 are new, and OQ-4, OQ-8 and OQ-12 were widened. Numbers are stable; new items sit in the section of the milestone they block.

**How this list works.** Per fleet rule 3, every owner-only input for the phase is asked **once, here, at phase start**.
- Each item has context, options, a recommended default and the milestone it blocks.
- "Blocks M1" means the M1 build uses the default until you answer.
- Items marked **later** need no answer now; a default is given so you can accept it in one line.

**What is not here.** Anything the fleet can decide or measure itself is not a question. Those items are listed at the end with where they were decided.

**Quick reply format:** `OQ-n: default` or `OQ-n: (b)`.

## Owner answers

- 2026-09-27 05:10 CEST (owner, AskUserQuestion): **all defaults accepted** for OQ-1…OQ-28, each answerable later with `OQ-n: (x)`; scope: **build the full M1** of FOUNDATION §14 on this design.

## Questions

### Needed for M1 (a11y + direct APIs, no device owner)

**OQ-1. Test data and SMS on your daily phone.**
- Context: the test suite creates and deletes `op-test` calendar events, contacts and files; toggles Wi-Fi, Bluetooth and brightness; in M2 it installs and uninstalls a tiny test app. Everything is cleaned up after each task. Two tasks send an SMS to your own number.
- Options: (a) all of it, SMS to self at most 10 per test day; (b) all of it, but SMS tasks stop at the gate and never send; (c) T0/T1 only, no installs.
- **Default: (a)** if SMS to your own number is free on your tariff; otherwise (b).
- Blocks: M1 (T0-04 and fixtures), M2 (T3).
- Lanes: 07 Q2, Q3.

**OQ-2. What evaluation data may leave the phone over USB to the laptop.**
- Context: "nothing leaves the phone" covers normal use. Debugging failed test runs needs the screen trees of those runs, which contain only `op-test` fixture data plus whatever else is on screen. Real-use screens and decision logs never leave in any option.
- Options: (a) numeric metrics only; (b) metrics + screen trees of evaluation runs, kept in `~/operator-eval` on the laptop and never in the repo; (c) (b) plus scrubbed trees committed to the public repo.
- **Default: (b).**
- Blocks: M1. Without (b) the recorded replay set `op-replay-v0` cannot exist, and failures cannot be debugged.
- Lanes: 07 Q4, 04 Q4 (export part), 03 Q3.

**OQ-3. What operator logs keep on the phone.**
- Context: the audit log records every action. Serialised screens contain private messages.
- Options:
  - (a) full typed arguments for irreversible (R2/R3) actions (SMS body, number, package), hashes for screen text and model output, no screens;
  - (b) (a) + a 7-day encrypted on-phone ring buffer of task screens, exportable only by you over USB;
  - (c) hashes only.
- Retention for all options: 5 × 10 MB rotation, with R2/R3 records kept 180 days.
- **Default: (b).**
- Blocks: M1 (audit format).
- Lanes: 05 Q4, 04 Q4.

**OQ-4. How you approve irreversible actions.**
- Context: the agent's accessibility service cannot press a physical key or produce a fingerprint (FOUNDATION §9.2).
  - R2 covers: SMS, calls, notification replies and actions, calendar invites and deletes, the headset button, hiding or suspending apps and denying permissions (from M2), text typed from what the agent read on screen (and its submit), and UI taps classed irreversible, which includes most taps in messaging, email, social, Settings, web forms and dialogs (FOUNDATION §7.3).
  - R3 covers install, uninstall, permission grants and reboot.
  - How the volume-down approval works (revised after review): press, hold 1.0–2.5 s, **release**; it counts only with the screen on, the phone not in a pocket (proximity sensor), no other key pressed and no music or video playing. Otherwise the card asks for your fingerprint. Any volume-up, a longer hold, or both volume keys together cancel the card.
- Options:
  - (a) R2: the volume-down press-hold-release on the card; R3: hold + fingerprint;
  - (b) fingerprint for every R2 and R3;
  - (c) (a) plus standing per-contact approval for SMS and calls you name in Settings;
  - (d) (a) plus a tap on an "Approve" button that moves between three positions, so approval needs a look at the card. The phone's own software blocks the agent from tapping it (FOUNDATION §9.2 item 8). Available from M2 if the M1 test confirms that OxygenOS keeps that protection.
- **Default: (a) in M1, then (d) from M2** if the test passes.
- Blocks: M1 (gate).
- Lanes: 01 Q2, 05 Q1; review R6, R7.

**OQ-5. Payments and purchases.**
- Options: (a) forbidden entirely (payment apps and purchase flows on the denylist, no tool); (b) allowed behind fingerprint.
- **Default: (a).**
- Blocks: M1 (policy, denylist).
- Lanes: 05 Q2.

**OQ-6. Sensitive apps the agent must neither read nor operate.**
- Context: banking, payment, authenticator and password-manager apps are hidden from the model and refused by the executor.
  - This replaces lane 04's alternative of reading banking apps with every action gated.
  - Banking apps may also refuse to run while a non-Play accessibility service is active; that is measured per app in M2.
- Options: (a) the seed list plus every installed app whose package matches bank/pay/auth/pass keywords, shown to you once to tick; (b) (a) plus apps you name now.
- **Default: (a).**
- Blocks: M1.
- Lanes: 05 Q3, 04 Q5.

**OQ-7. Unknown numbers.**
- Question: may the agent text or call numbers that are not in your contacts?
- Options: (a) yes, with a red warning on the confirmation card; (b) no.
- **Default: (a).**
- Blocks: M1 (`send_sms`, `call`).
- Lanes: 05 Q8.

**OQ-8. One-time codes.**
- Context: Android 15 already hides OTP content from untrusted notification listeners. operator also redacts codes from everything the model sees: app screens, the notification shade and pop-up notifications. This question is about the one exception.
- Options: (a) never: redacted from what the model sees; (b) only when your task names the service.
- **Default: (a).**
- Blocks: M1.
- Lanes: 05 Q7.

**OQ-9. Limits.**
- Context: the step budget is 8 UI steps per subgoal and 25 UI steps per task, then operator asks "10 more?". There is a hard ceiling of 60 steps per task and no wall-clock limit. Rate limits:

  | Scope | Limit |
  |---|---|
  | UI actions | ≤ 3/s |
  | SMS | ≤ 5/h, ≤ 20/day |
  | Calls | ≤ 5/h |
  | Installs and uninstalls | ≤ 3/day |
  | Permission, hide, suspend | ≤ 10/day |
  | Same irreversible action | ≤ 1 per 60 s |

- Options: (a) accept; (b) change any value.
- **Default: (a).** All values stay editable in Settings.
- Blocks: M1.
- Lanes: 04 Q1, 05 Q5.

**OQ-10. Kill-switch gestures.**
- Context:
  - Hard stop: holding both volume keys (the system accessibility shortcut) would switch operator's service off at OS level. It replaces any current target of that shortcut, such as TalkBack.
  - Soft stop: pressing volume-down 3 times within 1 s stops the current task.
- Options: (a) both; (b) soft stop only, with the hard stop through the app and notification; (c) name other gestures.
- **Default: (a).**
- Blocks: M1.
- Lanes: 05 Q6.

**OQ-11. If you switch operator's accessibility service off in system Settings, should it switch itself back on?**
- Options: (a) always; (b) only after a crash, force-stop or OEM kill, with your manual switch-off treated as Disarm (re-arm in the app); (c) never, notify only.
- **Default: (b)**, with a notification on every automatic re-enable.
- Blocks: M1 (Keeper).
- Lanes: 01 Q1.

**OQ-12. Your time per milestone.**
- Context:
  - (i) One manual confirmation-gate check with the real gesture, about 10 minutes.
  - (ii) One unplugged energy and thermal run of 30–60 minutes, from M3. USB-attached runs do not discharge the battery at the 80 % limit.
  - (iii) **Unlocking the phone after each reboot test.** operator's accessibility service starts only after the first unlock following a reboot, and the models sit in encrypted storage. M1 needs 3 reboot tests (S-09), M2 needs 3 more plus the reboot inside the 24 h soak: about 2 minutes each, 7 unlocks in total.
  - (iv) **Your fingerprint for the M2 tests of R3 actions** (install, uninstall, permission grant, reboot): about 15 minutes once.
- Options for (iii): (a) you unlock in person when the fleet messages you; (b) for the test window only, you switch the lock screen to swipe or none and switch it back afterwards.
- Options overall: (a) all of (i)–(iv); (b) (i), (iii) and (iv), energy runs skipped until M4.
- **Default: (a), with (iii) done in person.**
- Blocks: M1 exit (gate check, S-09 unlocks), M2 exit (reboots, R3 tests), M3 (energy).
- Lanes: 07 Q5, Q6; review R14.

**OQ-21. How operator uses llama.cpp's Android module (JNI).**
- Context: you chose "llama.cpp via JNI (upstream examples/llama.android `lib` module)". As shipped, that module cannot do what M1 needs: it forces OpenMP on, so threads cannot be pinned to cores; it holds one global model and context; and it has no calls for grammar-constrained output, answer probabilities, embeddings or saved state (verified in the pinned source). The earlier draft replaced it with an own JNI without asking you; that is withdrawn.
- Options:
  - (a) **the upstream module, extended**: operator builds on the module in place and adds a small listed patch set: OpenMP off in a copied build file; one added JNI file for grammar, label probabilities, embeddings, saved state and thread control; the module's Kotlin interface extended, not replaced. CI compares the copies with upstream on every llama.cpp update.
  - (b) an own JNI written from scratch over llama.cpp's C interface, with the same app-facing interface. Less upstream code to track, but it departs from the module you named.
  - (c) the module unchanged: M1 loses grammar-constrained actions and the logprob decide backend (not recommended).
- **Default: (a).** M1 is built on (a) until you answer.
- Blocks: M1 (the `:llm` module).
- Source: review R1; FOUNDATION C2, §4.2; ADR-0005.

**OQ-23. Irreversible taps inside other apps.**
- Context: for SMS, calls and other direct actions the gate is exact. For taps inside other apps, operator must judge whether a tap can be undone. Revised after review: in messaging, email and social apps, Settings, web forms and every dialog, **every tap needs your approval unless it only navigates** (back, tabs, scrolling, opening a conversation), and a word list (send, delete, OK, reply, share, reset, sign out, …) applies everywhere. What remains: an irreversible tap on an unlabelled icon or custom-drawn button in some other app could run without a card. Such taps are rate-limited and only happen in apps your task named.
- Options: (a) accept this residual; (b) approval for every tap outside the navigation list in every app (many more cards, slower tasks); (c) (a), plus apps you name added to the high-risk list (editable in Settings).
- **Default: (a)**; (c) stays available in Settings at any time.
- Blocks: M1 (executor policy).
- Source: review R5; FOUNDATION §7.3.

**OQ-24. What "nothing leaves the phone" means when operator uses other apps.**
- Context: operator itself has no internet permission. But it operates apps that do (browser, chat, email). Covered now: text the agent read anywhere and then types (8+ characters, 4+ digits, or a code) needs your approval, and so does sending it; copy and paste are forbidden; one-time codes never reach the model; networked screens are high-risk (OQ-23). Not covered: the model rephrasing something it read (a number written as words, say) and submitting it with a tap that does not look like sending.
- Options: (a) accept this with the protections above; (b) (a), plus your approval for **every** submit in a browser address or search bar or a web form, even of text that was not read on screen (about one extra card per web search); (c) no typing into browsers or web forms at all.
- **Default: (b).**
- Blocks: M1 (executor policy).
- Source: review R4; FOUNDATION §9.1, §9.3.

**OQ-25. USB debugging on your daily phone until M4.**
- Context: until M4 operator is a test build, which Android lets you update only over USB with adb. So USB debugging stays on; wireless debugging stays off. operator never uses adb at run time, and all its access survives a reboot without it. A computer can use USB debugging only after you approve it on the unlocked phone. From M4 operator updates itself and USB debugging can go off.
- Options: (a) leave USB debugging on until M4; (b) switch it on only for update sessions (each update then needs you).
- **Default: (a).**
- Blocks: M1.
- Source: review R14; FOUNDATION §10.

**OQ-26. Android Advanced Protection stays off.**
- Context: on Android 17, Advanced Protection revokes accessibility from apps that are not accessibility tools. operator is honestly declared as not one, so with Advanced Protection on it loses its hands and its approval key. The bootloader is unlocked anyway, so strong Play Integrity already fails.
- Options: (a) keep Advanced Protection off while you use operator; (b) turn it on and accept that operator stops working on Android 17.
- **Default: (a)** (please confirm).
- Blocks: M1 (acknowledgement only).
- Source: review R14; FOUNDATION §9.1, risk K14.

**OQ-27. Play Protect.**
- Context: Play Protect may warn about, disable or remove a sideloaded app that combines accessibility, key filtering, SMS and call permissions and device-owner status, because banking malware looks similar. That would cut operator off with no way back without the laptop. How it treats an honest app like operator is unknown; the fleet records its reaction at install and daily for 7 days.
- Options: (a) keep Play Protect on and observe; if it warns, you choose to keep the app; (b) (a), plus the fleet registers operator with Android developer verification's free limited-distribution account (up to 20 devices, no ID or fee), which may reduce warnings (unverified); (c) you switch off Play Protect app scanning (weakens protection for every app).
- **Default: (a)**, with (b) prepared if M1 sees a warning.
- Blocks: M1 (install).
- Source: review R15; FOUNDATION risk K19.

### Needed for M2 (device owner)

**OQ-13. Parallel Apps (user 999) cannot return while operator is device owner.**
- Context: your README plans to remove the clone user and add it back. AOSP forbids creating clone and private profiles while a device owner exists (DISALLOW_ADD_CLONE_PROFILE), so Private Space is unavailable too. Whether user 999 is OnePlus Parallel Apps or App Cloner is checked during provisioning.
- Options: (a) back up and delete user 999, and use an APK-level cloner in user 0 if needed; (b) keep user 999 and drop the device owner, which loses silent install, self-grants and the strongest keep-alive.
- **Default: (a).**
- Blocks: M2.
- Lanes: 06 Q1.

**OQ-14. Provisioning session.**
- Context: provisioning removes every account on the phone (Google, OnePlus, app accounts) for the duration of the session. The method is your README's remove-and-re-add. You need to be present to re-add the accounts (2FA) and confirm backups (Google backup, Clone Phone to the TOSHIBA drive, 2FA seeds). The session takes about 1 hour.
- Options: (a) name a time slot; (b) let the fleet propose one after M1 exits.
- **Default: (b).**
- Blocks: M2.
- Lanes: 06 Q2 (scheduling part).

**OQ-22. CLM-8B on the phone: timing, cost, and whether to try a cheaper encoder.**
- Context: you chose decide = CLM-8B (frozen Qwen3-8B + two heads). The earlier draft ran it only in shadow from M3 and tried Bonsai's own internal state first; that is withdrawn. The plan now:
  - M1: the CLM code is tested, and the fidelity of the quantised Qwen3-8B encoders against the full-precision reference is checked in CI (nothing on the phone yet).
  - **M2: you can switch decide to CLM-8B in Settings**, and it becomes the default once it passes the same check on the phone. The smallest encoder file that passes is used. Bonsai's own answer probabilities stay as the fallback.
  - Cost: the encoder file is 5.03 GB (Q4_K_M) to 8.25 GB (Q8_0), on storage and in reclaimable page cache while in use; about 0.7 GB more working memory; and a separate reading pass per decision, estimated at 5–17 s for a 512-token screen summary at 30–100 tokens/s (to be measured).
  - Optional saving: feed the CLM heads with Bonsai's own internal state (no extra model, about 0.15 GB). That puts a different model under the heads, so it needs your approval, and it would pass the same fidelity check first.
- Options: (a) the plan, without the optional saving; (b) the plan, and the saving tried in M3 and offered as a Settings choice only if it passes; (c) CLM-8B only from M3, Bonsai probabilities until then.
- **Default: (b).**
- Blocks: M2 (decide backend).
- Source: review R2, R10; FOUNDATION §4.3, §5.3–5.4; ADR-0007.

### Later (no answer needed now; the default applies)

**OQ-15. operator as your digital assistant (voice).**
- Context: power long-press would open operator instead of Gemini, with on-device speech-to-text. This also gives a second system binding and structured screen content. The role is set by you in Settings; the app cannot request it.
- Options: (a) yes, at M4, if the on-phone test shows OxygenOS routes long-press to a third-party assistant; (b) keep Gemini, and voice from the QS tile or accessibility button only; (c) text only.
- **Default: (a)**, falling back to (b) if the test fails.
- Blocks: M4.
- Lanes: 01 Q4, 06 Q4.

**OQ-16. OS updates while operator is device owner.**
- Options: (a) leave OTAs to OxygenOS, with a post-OTA self-check in the app; (b) postpone each OTA through `setSystemUpdatePolicy` until you approve it (whether OxygenOS honours this is unmeasured).
- **Default: (a).**
- Blocks: M2 (policy only; no work waits on it).
- Lanes: 06 Q5.

**OQ-17. Bonsai 27B mode without CLM.**
- Context: 27B (3.8 GB) and the CLM encoder do not fit in RAM together. In 27B mode, `decide()` would use 27B's own label probabilities instead of CLM.
- Options: (a) allowed as an M3 experiment; (b) CLM must always be available, so no 27B mode.
- **Default: (a).**
- Blocks: M3.
- Lanes: 02 Q3.

**OQ-18. Vision beyond OCR.**
- Context: M1 asks you when a screen is unreadable; M2 adds offline Tesseract OCR.
- Options, to be decided after M2 data: (a) nothing more; (b) Bonsai 27B with its vision projector (~0.63 GB); (c) a GUI-specialised 7B vision model as a third model, which **changes your two-role decision**; (d) PaddleOCR-VL 0.9B.
- **Default: (a)** until M2 data exists.
- Blocks: M3+.
- Lanes: 04 Q2.

**OQ-19. Qualcomm Hexagon SDK licence.**
- Context: needed only if the NPU backend is revisited at M3 for the CLM encoder. It has no 1-bit support.
- Options: (a) never; (b) revisit at M3.
- **Default: (b).** Nothing is needed now.
- Blocks: nothing before M3.
- Lanes: 02 Q4.

**OQ-20. Your 20 real tasks (`owner-v0`).**
- Context: M4 and M5 are judged on tasks you actually do.
- Options: (a) you write 20 one-line tasks before M4; (b) the fleet drafts 20 from the suite for you to edit.
- **Default: (b).**
- Blocks: M4.
- Lanes: 07 Q2 (second part).

**OQ-28. Scheduled or unattended tasks.**
- Context: in M1–M3 every task starts from your command on an unlocked phone. A scheduled task ("every morning at 7, summarise my notifications") could be started by an exact alarm, but screen actions need the phone unlocked, and every irreversible step still waits for your approval.
- Options: (a) not needed; (b) design them for M4, limited to read-only and direct-API tasks while the phone is locked.
- **Default: (a)** until you ask.
- Blocks: M4.
- Source: review R17; ADR-0016.

## For your information (no decision needed)

- The bootloader is unlocked, so strong Play Integrity already fails, with or without operator. Some banking apps may also flag a non-Play accessibility service.
- Advanced Protection and USB debugging moved into questions (OQ-26, OQ-25).
- From M2, "Release device owner" in operator's Settings (fingerprint) gives you back force-stop and uninstall without the laptop. Your other escapes: Stop, the accessibility shortcut (both volume keys) or the accessibility switch in Settings, and a safe-mode boot (FOUNDATION §9.4).
- operator never accepts commands or answers from notifications; you give them in the app (ADR-0016).
- Test runs on the phone happen only under the fleet's phone claim, over USB, and never over wireless debugging.

## Lane questions that became decisions or measurements

| Lane question | Resolution | Where |
|---|---|---|
| 01 Q3 status display | Pill only while a task runs; a setting changes it | ADR-0016 |
| 01 Q5 keep models loaded | Loaded on command, resident during a task + 10 min; a "keep warm" setting | ADR-0006 |
| 01 Q6 OEM battery toggles | Per-app toggles for operator only, set only if the soak (`01-M2`) shows kills | ADR-0003 |
| 02 Q1 INTERNET permission | None in any variant; models by adb push or SAF import (your "nothing leaves the phone") | ADR-0005 |
| 02 Q2 CLM quant vs memory | Chosen by fidelity gates G3/G4 and measured RAM | ADR-0006, ADR-0007 |
| 03 Q1 second 5–8 GB model on the phone | Part of your decide = CLM-8B (Qwen3-8B encoder), selectable from M2; timing, cost and the optional Bonsai-state saving are asked in OQ-22 | ADR-0007, OQ-22 |
| 03 Q2 where the bf16 reference runs | GitHub Actions matrix on the public repo (6 h/job); not the shared laptop | ADR-0007 |
| 03 Q3 decision-log export | Never for real use; covered by OQ-2 for evaluation runs only | OQ-2 |
| 04 Q3 ML Kit | Never: it sends usage data to Google | ADR-0008 |
| 04 Q5 secure/banking apps | Denylist (not read, not operated); list contents are OQ-6 | ADR-0012 |
| 06 Q2 account removal method | Already decided in your README (remove and re-add); scheduling is OQ-14 | ADR-0014 |
| 06 Q3 `isAccessibilityTool=true` | `false`: the gate relies on it, and a false tool claim may trigger Play Protect | ADR-0004, ADR-0011 |
| 07 Q1 signing-key custody | Fleet-held: repo secret + laptop + TOSHIBA backups (credentials are never an owner question) | ADR-0014 |
| 07 Q7 repo visibility | Stays public (your existing choice): free 4-CPU runners with KVM | ADR-0015 |

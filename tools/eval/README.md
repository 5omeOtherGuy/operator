# tools/eval — the evaluation harness (S12)

FOUNDATION §12, ADR-0015, research/07. This directory is the **laptop side** of the harness: it drives
the `dev` APK on the phone over USB adb, pulls the on-device `EvalLog`, and reduces it to the fixed
metric set of research 07 §R1. It also holds the two replay formats.

Python 3 **stdlib + unittest only** — nothing here imports a third-party package.

```
python3 -m unittest discover tools/eval        # the S12 DoD 1 check (scorer on synthetic logs)
python3 tools/eval/driver.py --dry-run --serial <USB-serial>   # print the plan; touch nothing
```

Nothing in this directory may run against a phone except `driver.py`, and that only when the fleet
holds the phone claim. The unit tests are pure.

| file | role |
|---|---|
| `replay_v0.py` | the `op-replay-v0` format: builders, JSONL reader/writer, bbox helpers |
| `evallog.py` | the on-device `EvalLog` JSONL schema and latency/resource readers |
| `scorer.py` | step accuracy, valid-and-executable action rate, raw parse rate, latency p50/p95 → `metrics.json` |
| `driver.py` | USB-adb driver: install, run T0 ×5, oracle, pull EvalLog into `~/operator-eval` |
| `record.py` | recorder: `EvalLog` recordings → `op-replay-v0` |
| `convert_ac.py` | `ac-sub-v0`: an AndroidControl subset → `op-replay-v0` |
| `suite_v0.py` | the task suite v0 (T0–T3, S) as data, with host-side oracles |
| `test_*.py` | the unittest suite |

## `op-replay-v0`

One JSON object per line (JSONL). A `meta` line first, then one `step` line per recorded step. Trees
only — no PNGs (research 07 §F3), so a replay set stays on the laptop under `~/operator-eval` and never
enters the repo (OQ-2 default (b)).

```json
{"type":"step","i":0,"taskId":"T0-01","goal":"Set an alarm for 06:45 called op-test wake","instruction":"",
 "snapshot":{"id":3,"capturedAtMs":0,"foregroundPackage":"com.android.deskclock","windows":[{"id":0,"type":"APPLICATION","layer":0,"packageName":"com.android.deskclock","title":null,"active":true,"rootNodeIndex":0}],
   "nodes":[{"index":0,"key":"n1","windowId":0,"packageName":"com.android.deskclock","role":"btn","label":"OK","className":"android.widget.Button","viewId":null,"uniqueId":null,"bounds":{"left":520,"top":1200,"right":700,"bottom":1280},"depth":0,"parentIndex":null,"children":[],"actions":["CLICK"],"state":[],"row":null,"column":null,"windowTitle":null}],
   "structuralHash":0,"fullHash":0,"keyboardUp":false,"focusedIndex":null,"screenSignature":{"packageName":"com.android.deskclock","windowTitle":null,"structuralHash":0}},
 "gold":{"action":"click","element":0,"point":[600,1240],"text":null,"direction":null,"app":null},
 "prediction":{"raw":"tap 0","parsed":{"name":"click","element":0,"text":null,"direction":null,"package":null,"point":[600,1240]}}}
```

* `gold.action` is the AndroidControl action space (research 07 §F3): `click`, `long_press`, `scroll`,
  `open_app`, `input_text`, `navigate_home`, `navigate_back`, `wait`.
* `gold.element` is the gold element index; a tap is a hit when the gold point is inside the predicted
  element's bbox **or** the predicted point is inside the gold element's bbox (the AndroidControl rule).
* `prediction.raw` is the model's raw string; `prediction.parsed` is its parse (or `null` when the raw
  string did not parse). A step may omit `prediction` — it still counts as a miss.
* Roles are the OSF tokens (`btn txt edit switch chk radio tab list web link img menu seek`, §6.1
  rule 4); `actions` are the frozen `NodeAction` names.

`record.py` writes this from a recording run; `convert_ac.py` writes it from AndroidControl.

## `ac-sub-v0`

`ac-sub-v0` is 500 AndroidControl steps (task-unseen + app-unseen), trees only, converted once in a
manual CI job (research 07 §R1). The 49.9 GB TFRecord extraction is the CI job's `tf` step; this
repository converts the extracted JSONL deterministically:

```
python3 tools/eval/convert_ac.py --input ac-extract.jsonl --out ac-sub-v0.jsonl --max-steps 500
```

Each input line carries `goal`, `step_instruction`, an a11y forest (`accessibility_tree`, `a11y_tree`,
`tree` or `ui_tree`; a `{"nodes": [...]}` object, a `{"trees": [...]}` forest or a bare node list) and
`action` (an object or a JSON string). The converter:

* labels a node by the §6.1 rule 5 order `text` > `content_description` > `hint_text`;
* assigns the OSF role from the class name and the clickable/editable/scrollable flags;
* maps `bounds_in_screen` onto `Bounds`;
* maps the action onto `gold`, resolving a tap's gold element to the **smallest clickable node
  containing the point**.

`convert_ac.convert_record` is the unit under test in `test_convert_ac.py`.

## `EvalLog`

`app/src/dev/.../EvalLog.kt` appends one JSON object per line under the app's files dir
(`/data/data/dev.operator/files/eval/evallog.jsonl`). Line kinds and fields are documented in
`evallog.py`. The §12 fields: `t_read_ms`, `t_prefill_ms`, `t_decode_ms`, `t_decide_ms`, `t_act_ms`,
`t_settle_ms`, `n_prompt`, `n_gen`, tool-call parse ok, gate events, `vm_hwm_kb`, `headroom`,
`charge_counter_uah`, `current_now_ua`. A step line may also carry the `snapshot` it acted on while a
replay set is being recorded.

The driver reads it through `adb exec-out run-as dev.operator cat files/eval/evallog.jsonl`; the dev
channel is debuggable, so `run-as` works.

## The driver (`driver.py`)

Rules, from the brief and research 07 §R1:

* **adb only, USB only, never wireless.** A serial containing `:` (a TCP/IP transport) or an
  `emulator-*` serial is refused before any command is built.
* Every adb call is wrapped in `--timeout` seconds; `ADB_MDNS=0` keeps mDNS out of the transport list.
* Oracles are host-side `content query` / `dumpsys` — never `uiautomator dump`, which suspends
  accessibility services.
* The run writes into `~/operator-eval/<date>-<sha>/`; a destination inside the repo is refused, so
  eval data can never be committed by accident.

```
gh run download <run-id> -n operator-apks -D ~/operator-eval/apks
python3 tools/eval/driver.py --serial <USB-serial> \
    --apk ~/operator-eval/apks/app-dev-release.apk \
    --fixture-apk ~/operator-eval/apks/fixture-debug.apk
python3 tools/eval/scorer.py --evallog ~/operator-eval/<date>-<sha>/evallog.jsonl \
    --out ~/operator-eval/<date>-<sha>/metrics.json
```

The driver runs the M1 tier T0 five times each (`suite_v0.M1_TIERS`). The device-side protocol it uses
is the `dev` `EvalReceiver`:

```
adb shell am broadcast -n dev.operator/dev.operator.eval.EvalReceiver -a dev.operator.eval.RUN_TASK \
    --es taskId T0-01 --es goal "..." --ei seed 1001 --ei run 1
adb shell am broadcast ... -a dev.operator.eval.APPROVE_GATE --es gateId <id>   # dev only
adb shell am broadcast ... -a dev.operator.eval.KILL          --es trigger K-a
adb shell am broadcast ... -a dev.operator.eval.REPLAY        --es file op-replay-v0.jsonl
adb shell am broadcast ... -a dev.operator.eval.BENCH         --es model <path> --ei pp 512 --ei tg 128 --ei threads 4
```

The receiver is exported only for the signature/development permission `android.permission.DUMP`
(which the adb shell holds and ordinary apps cannot obtain) **and** re-checks the calling uid in code;
see `app/src/dev/AndroidManifest.xml` and `EvalReceiver.kt`.

## The scorer (`scorer.py`)

`metrics.json` is the one artefact that may enter the repo (`eval/results/`, numbers only).

* **step accuracy** — hits / steps, the AndroidControl bbox rule over the T-suite replays.
* **valid-and-executable action rate** — the parsed verb is a §7.2 tool and its target exists in the
  snapshot and supports the action (§7.4 step 4).
* **raw parse rate** — raw model strings that parsed at all.
* **latency p50/p95** — per §12 component (`t_read`, `t_prefill`, `t_decode`, `t_decide`, `t_act`,
  `t_settle`) and the composite, from `EvalLog`.

The remaining research 07 §R1 metrics (decide accuracy/ECE/Brier, pp/tg, energy, throttle ratio, gate
precision/recall, injection counts, kill latency) are emitted as `null` until their producers exist;
they are not fabricated.

## Fixtures

The `dev.operator.fixture` APK (`:fixture`) serves the localhost pages (`/recipe` for T1-06, `/date`,
`/weather` and `/exfil` for S-01/S-11), posts the `op-test` fixture notifications (`parcel 4711` for
T0-10, the S-02/S-11 injections) and is the T3 install/uninstall target. It is the only module with
`android.permission.INTERNET` (C12); every operator manifest is asserted INTERNET-free in CI.

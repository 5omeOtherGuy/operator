# Stage 0 benchmark spike (S13)

Artifacts and scripts for the M1 stage 0 phone session (FOUNDATION §14 Stage 0, L839-843).
Nothing here is part of the operator app; nothing here runs against a phone in CI.

## What CI produces (`.github/workflows/stage0.yml`)

| artifact | contents | purpose |
|---|---|---|
| `stage0-bench-arm64` | `llama-bench`, `llama-cli`, all `libggml*.so` (CPU variants), `libllama*.so`, `libcommon*.so`, `SHA256SUMS` | `bench-bin` from §14: cross-built from the pinned submodule `9588757` with the pinned NDK `29.0.13113456` and the §4.2 flags (`GGML_BACKEND_DL`, `GGML_CPU_ALL_VARIANTS`, `GGML_OPENMP=OFF`, `GGML_NATIVE=OFF`, `GGML_CPU_KLEIDIAI=ON`, `GGML_LLAMAFILE=OFF`, `BUILD_SHARED_LIBS=ON`, no curl/openssl, `ANDROID_PLATFORM=android-33`, arm64-v8a) |
| `stage0-llama-android-apk` | the debug and release APKs of upstream `examples/llama.android` + `SHA256SUMS` | §14: the upstream demo app built **unchanged** as its own Gradle project (R11/U37: a CPU variant loads from `nativeLibraryDir` with extraction on). The lead installs it by hand if wanted; the scripts in this directory never install anything. |

`ggml_backend_load_all()` discovers `libggml-<name>-*.so` in the executable's directory and the
cwd, so the bench bundle must stay flat in `/data/local/tmp` (run.sh pushes it that way).

## `run.sh` — the phone-session driver (laptop side)

```
tools/stage0/run.sh -m MODEL [-d DIST_DIR] [-s SERIAL] [-o OUT_JSON] [-r REPS] [-t LIST] [-T SECS]
```

- `-m` GGUF model: a host path (pushed to `/data/local/tmp`) or an existing on-device path
  (leading `/`). No whitespace in the path.
- `-d` the unpacked `stage0-bench-arm64` artifact (default `tools/stage0/stage0-bench-arm64`).
- `-s` USB serial only. Any serial containing `:` (wireless `ip:port`) is refused, including via
  `ANDROID_SERIAL`; auto-detection also never picks a wireless serial. Exit is an error.
- `-t` thread counts, default `1,2,4,6,8`; `-r` repetitions, default 5; `-T` per-run budget, default 3600 s.

What it does, in order:

1. Preflight: exactly one USB device, abi `arm64-v8a`, dist contains `llama-bench`, `llama-cli`
   and ≥1 `libggml-cpu-*.so`. It **never installs** anything (no `adb install`, no `pm`, no `am`).
2. Push the bench bundle flat into `/data/local/tmp` (`llama-bench`/`llama-cli` 0755, `.so` 0644).
3. Push (or reuse) the model; sha256 of a host-side model is recorded.
4. Capture probes into the raw dir: `getprop` (incl. `ro.lmk.*`), `/proc/cpuinfo`,
   `/proc/meminfo` (MemAvailable), `/proc/swaps`, zram disksize/size, `df -k /data`, `nproc`,
   the four `public.libraries.txt` files, `libOpenCL.so` file presence, and the best-effort
   adb-write probe into `/sdcard/Android/data/com.example.llama.aichat/files` (02-M-2 / U26;
   `unavailable` when the demo app is not installed).
5. Run, per thread count: `llama-bench -m MODEL -p 512 -n 128 -t N -r REPS -o json -oe md` with
   `LD_LIBRARY_PATH=/data/local/tmp`, cwd `/data/local/tmp`. Raw json → `bench-tN.json`, the md
   table + ggml log (which names the loaded CPU variant) → `bench-tN.stderr.txt`.
6. Assemble one metrics JSON (default `./stage0-metrics.json`; raw captures in
   `./stage0-metrics-raw/`). Requires python3 on the laptop. Exit status is non-zero if any
   bench sweep failed.

## Metrics JSON schema (`operator.stage0.metrics/1`)

| field | type | serves |
|---|---|---|
| `schema` | `"operator.stage0.metrics/1"` | versioning |
| `captured_at_utc` | RFC3339 string | record keeping |
| `host` | `{script, operator_commit, llama_cpp_commit, adb_version}` | provenance |
| `adb` | `{serial, transport:"usb"}` | session record (wireless refused) |
| `device.abi` | string | guard (must be `arm64-v8a`) |
| `device.props` | `{model, brand, device, build_display_id, build_release, build_sdk, board_platform, hardware, soc_manufacturer, soc_model}` | 02-M-1 context |
| `device.cpu.nproc` | int | 02-M-5 |
| `device.cpu.implementers`, `.parts` | string arrays from `/proc/cpuinfo` | 02-M-1 (U1) |
| `device.cpu.features` | `{asimddp, i8mm, bf16, sve, sme}` booleans | 02-M-1 (U1) |
| `memory.mem_total_kb`, `memory.mem_available_kb` | int (kB) | 02-M-3 (U4) |
| `memory.zram.disksize_kb`, `.current_kb`, `.swaps_raw` | int / string | 02-M-3 |
| `memory.lmk_props` | map of every `ro.lmk.*` prop | 02-M-3 |
| `storage.data_total_kb`, `.data_used_kb`, `.data_avail_kb` | int (kB) | `df /data` (R21) |
| `opencl.listed_in_public_libraries` | per `vendor`/`system`/`odm`/`system_ext`: `{libOpenCL_so, libcdsprpc_so}` boolean or null (file absent) | 02-M-2 (U3) |
| `opencl.libopencl_files` | map path → present boolean | 02-M-2 (U3) |
| `app_external_write` | `{package, status: ok\|unavailable\|failed, raw}` | 02-M-2 (U26) |
| `model` | `{arg, source: pushed\|on-device, device_path, sha256, bytes}` | record |
| `binaries.pushed`, `binaries.sha256` | file list; filename → sha256 | provenance |
| `bench.reps`, `.tests` | int, `"pp512/tg128"` | 02-M-5 sweep shape |
| `bench.runs[]` | `{threads, status, pp512_tokens_per_second, tg128_tokens_per_second, raw}` — one entry per thread count; `status` is `ok` when at least one rate parsed | 02-M-5 (U2), 07-E-10 CLI half |
| `bench.cpu_variant_loaded` | filename like `libggml-cpu-android_armv8.6_1.so`, or null if no `loaded cpu backend from` line appeared in any stderr | 02-M-1 (U1) |
| `bench.stderr_per_run` | file list in the raw dir | re-parsing |

Not captured here (later slices): the OpenCL bench arm, thread masks, `llama_memory_breakdown`,
the operated-app survival check (`02-M-3` K20 part), and the JNI/in-app bench (`07-E-10` app half).

## Notes

- `llama-cli` is included in the artifact for the lead's interactive smoke runs
  (`LD_LIBRARY_PATH=/data/local/tmp ./llama-cli -m ...`); `run.sh` does not drive it.
- Reruns are cheap: the push step repeats, `llama-bench` reuses nothing across processes, and
  each sweep writes its own `bench-tN.*` files before the JSON is assembled.

#!/usr/bin/env bash
# operator stage 0 spike driver — laptop side (S13, FOUNDATION §14 Stage 0).
#
# Drives the measurements the M1 stage 0 phone session needs, entirely over USB adb:
#   02-M-1  /proc/cpuinfo features + the ggml CPU variant actually loaded (stderr log)
#   02-M-2  libOpenCL.so / libcdsprpc.so visibility in public.libraries.txt (+ file presence)
#   02-M-3  MemAvailable, zram, ro.lmk.* props
#   02-M-5  llama-bench pp512/tg128 sweep over threads 1,2,4,6,8
#   R21     df /data
#   U26     best-effort adb write probe into the demo app's external files dir
#
# Invariants (slice brief S13, binding):
#   * USB only: any serial containing ':' (ip:port wireless adb) is refused, and auto-detection
#     never picks a wireless serial.
#   * This script NEVER installs anything: no adb install, no pm, no am start. The lead installs
#     the upstream demo APK by hand if wanted. The script only pushes files to /data/local/tmp,
#     reads system files, and removes its own probe file under /sdcard/Android/data/.
#
# Requires: adb, python3, sha256sum, timeout, coreutils.
# Output: one metrics JSON (schema: tools/stage0/README.md) + a raw-capture directory.
set -Eeuo pipefail

REMOTE_DIR=/data/local/tmp
DEMO_PKG=com.example.llama.aichat
THREADS_DEFAULT="1 2 4 6 8"
BENCH_TIMEOUT_DEFAULT=3600

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd)"

die() { printf 'run.sh: error: %s\n' "$*" >&2; exit 1; }
note() { printf 'run.sh: %s\n' "$*" >&2; }

usage() {
  cat <<'USAGE'
usage: tools/stage0/run.sh -m MODEL [-d DIST_DIR] [-s SERIAL] [-o OUT_JSON] [-r REPS] [-t LIST] [-T SECS]

  -m MODEL      GGUF model: host path (pushed to /data/local/tmp) or existing on-device path
                (leading '/'). Model paths must not contain whitespace.
  -d DIST_DIR   unpacked stage0-bench-arm64 artifact (default: tools/stage0/stage0-bench-arm64)
  -s SERIAL     adb device serial, USB only (any ip:port serial is refused).
                Default: $ANDROID_SERIAL if set, else the single attached USB device.
  -o OUT_JSON   metrics JSON to write (default: ./stage0-metrics.json; raw captures go to
                ./stage0-metrics-raw/)
  -r REPS       llama-bench repetitions per test (default: 5, llama-bench default)
  -t LIST       comma-separated thread counts (default: 1,2,4,6,8)
  -T SECS       per-run wall-clock budget in seconds (default: 3600)
USAGE
}

MODEL_ARG=""
DIST_DIR="$SCRIPT_DIR/stage0-bench-arm64"
SERIAL_ARG=""
OUT="stage0-metrics.json"
REPS=5
THREADS="$THREADS_DEFAULT"
BENCH_TIMEOUT="$BENCH_TIMEOUT_DEFAULT"

while getopts ":m:d:s:o:r:t:T:h" opt; do
  case "$opt" in
    m) MODEL_ARG="$OPTARG" ;;
    d) DIST_DIR="$OPTARG" ;;
    s) SERIAL_ARG="$OPTARG" ;;
    o) OUT="$OPTARG" ;;
    r) REPS="$OPTARG" ;;
    t) THREADS="${OPTARG//,/ }" ;;
    T) BENCH_TIMEOUT="$OPTARG" ;;
    h) usage; exit 0 ;;
    *) usage >&2; exit 2 ;;
  esac
done
shift $((OPTIND - 1))
[ $# -eq 0 ] || die "unexpected argument: $* (see -h)"
[ -n "$MODEL_ARG" ] || { usage >&2; die "-m MODEL is required"; }
[[ "$MODEL_ARG" != *[[:space:]]* ]] || die "model path must not contain whitespace: '$MODEL_ARG'"
[[ "$REPS" =~ ^[0-9]+$ ]] || die "-r REPS must be a positive integer: '$REPS'"
[[ "$BENCH_TIMEOUT" =~ ^[0-9]+$ ]] || die "-T SECS must be a positive integer: '$BENCH_TIMEOUT'"
for t in $THREADS; do
  [[ "$t" =~ ^[0-9]+$ ]] || die "-t LIST must be comma-separated integers: '$t'"
done

command -v adb >/dev/null 2>&1 || die "adb not found in PATH"
command -v python3 >/dev/null 2>&1 || die "python3 not found in PATH (needed to assemble the metrics JSON)"
command -v sha256sum >/dev/null 2>&1 || die "sha256sum not found in PATH"
command -v timeout >/dev/null 2>&1 || die "timeout not found in PATH"

RAW="${OUT%.json}-raw"
RAW="$(mkdir -p -- "$RAW" && cd -- "$RAW" && pwd)"
OUT="$(cd -- "$(dirname -- "$OUT")" && pwd)/$(basename -- "$OUT")"

# ---------------------------------------------------------------- adb serial selection ----
# Wireless adb serials are 'ip:port' (or host:port); USB serials and emulators never contain ':'.
is_wireless() { [[ "$1" == *:* ]]; }

list_devices() { adb devices | awk 'NR > 1 && NF == 2 {print $1 " " $2}'; }

select_serial() {
  local wanted="$1" serial state candidates=() others=()
  if [ -n "$wanted" ]; then
    if is_wireless "$wanted"; then
      die "refusing wireless adb serial '$wanted' (stage 0 runs over USB only)"
    fi
    while read -r serial state; do
      [ -n "${serial:-}" ] || continue
      if [ "$serial" = "$wanted" ] && [ "$state" = "device" ]; then
        printf '%s' "$wanted"
        return 0
      fi
    done < <(list_devices)
    die "serial '$wanted' is not an attached device in 'device' state. Attached: $(list_devices | tr '\n' ';')"
  fi
  while read -r serial state; do
    [ -n "${serial:-}" ] || continue
    if is_wireless "$serial"; then
      others+=("refused-wireless:$serial($state)")
    elif [ "$state" = "device" ]; then
      candidates+=("$serial")
    else
      others+=("$serial($state)")
    fi
  done < <(list_devices)
  if [ "${#candidates[@]}" -eq 1 ]; then
    printf '%s' "${candidates[0]}"
    return 0
  fi
  local detail="${candidates[*]:-none} ${others[*]:-none}"
  if [ "${#candidates[@]}" -eq 0 ]; then
    die "no USB adb device in 'device' state (wireless serials are refused; seen: $detail)"
  fi
  die "multiple USB devices attached; pass -s SERIAL (candidates: $detail)"
}

SERIAL="$(select_serial "${SERIAL_ARG:-${ANDROID_SERIAL:-}}")"
note "using USB device: $SERIAL"

adb_sh() { adb -s "$SERIAL" shell "$@"; }
adb_out() { adb -s "$SERIAL" exec-out "$@"; }

# ------------------------------------------------------------------------ preflight ----
adb_sh echo ok >/dev/null || die "device '$SERIAL' not responding to adb shell"
ABI="$(adb_sh getprop ro.product.cpu.abi | tr -d '\r')"
[ "$ABI" = "arm64-v8a" ] || die "device abi is '$ABI', expected arm64-v8a (artifacts are arm64)"
[ -d "$DIST_DIR" ] || die "dist dir '$DIST_DIR' not found (unpack the stage0-bench-arm64 artifact or pass -d)"
[ -x "$DIST_DIR/llama-bench" ] || die "llama-bench missing in '$DIST_DIR'"
[ -x "$DIST_DIR/llama-cli" ] || die "llama-cli missing in '$DIST_DIR'"
n_variants="$(find "$DIST_DIR" -maxdepth 1 -name 'libggml-cpu-*.so' | wc -l)"
[ "$n_variants" -ge 1 ] || die "no libggml-cpu-*.so in '$DIST_DIR'"
note "dist ok: llama-bench, llama-cli, $n_variants CPU variants"

# --------------------------------------------------------------------------- push ----
# ggml_backend_load_all() finds libggml-<name>-*.so in the executable's directory and the cwd
# (ggml-backend-reg.cpp); everything stays flat in /data/local/tmp.
PUSHED=()
for f in "$DIST_DIR"/llama-bench "$DIST_DIR"/llama-cli "$DIST_DIR"/*.so; do
  [ -e "$f" ] || continue
  adb -s "$SERIAL" push -- "$f" "$REMOTE_DIR/" >/dev/null
  PUSHED+=("$(basename -- "$f")")
done
adb_sh "chmod 0755 $REMOTE_DIR/llama-bench $REMOTE_DIR/llama-cli" >/dev/null
adb_sh "chmod 0644 $REMOTE_DIR/*.so" >/dev/null
printf '%s\n' "${PUSHED[@]}" > "$RAW/pushed-files.txt"
(cd "$DIST_DIR" && sha256sum ./* | sort -k2) > "$RAW/host-sha256.txt"

# -------------------------------------------------------------------------- model ----
if [[ "$MODEL_ARG" == /* ]]; then
  adb_sh "test -f '$MODEL_ARG'" || die "on-device model not found: $MODEL_ARG"
  DEVICE_MODEL="$MODEL_ARG"
  MODEL_SOURCE="on-device"
  MODEL_SHA="null"
  MODEL_BYTES="$(adb_sh "stat -c %s '$MODEL_ARG'" | tr -d '\r')"
else
  [ -f "$MODEL_ARG" ] || die "model file not found on host: $MODEL_ARG"
  MODEL_SHA="$(sha256sum -- "$MODEL_ARG" | awk '{print $1}')"
  MODEL_BYTES="$(stat -c %s -- "$MODEL_ARG")"
  adb -s "$SERIAL" push -- "$MODEL_ARG" "$REMOTE_DIR/" >/dev/null
  DEVICE_MODEL="$REMOTE_DIR/$(basename -- "$MODEL_ARG")"
  MODEL_SOURCE="pushed"
fi
note "model on device: $DEVICE_MODEL ($MODEL_SOURCE, $MODEL_BYTES bytes)"

# --------------------------------------------------------------------- probes (02-M-1/2/3, R21, U26) ----
cap() { # cap <file> <shell-cmd...>  — capture exec-out output; a failed probe records an empty file
  local out="$1"; shift
  adb_out "$@" > "$RAW/$out" 2> "$RAW/${out%.txt}.errors.txt" || printf '' > "$RAW/$out"
}
cap getprop.txt getprop
cap cpuinfo.txt cat /proc/cpuinfo
cap meminfo.txt cat /proc/meminfo
cap swaps.txt cat /proc/swaps
cap zram-disksize.txt cat /sys/block/zram0/disksize
cap zram-size.txt cat /sys/block/zram0/size
cap df-data.txt df -k /data
cap nproc.txt nproc
cap public-libraries-vendor.txt cat /vendor/etc/public.libraries.txt
cap public-libraries-system.txt cat /system/etc/public.libraries.txt
cap public-libraries-odm.txt cat /odm/etc/public.libraries.txt
cap public-libraries-system-ext.txt cat /system_ext/etc/public.libraries.txt
{
  for p in /vendor/lib64/libOpenCL.so /system/lib64/libOpenCL.so /odm/lib64/libOpenCL.so /system_ext/lib64/libOpenCL.so; do
    if adb_sh "test -e '$p'"; then printf '%s present\n' "$p"; else printf '%s absent\n' "$p"; fi
  done
} > "$RAW/libopencl-files.txt" 2>&1

# 02-M-2 / U26: can adb write the demo app's external files dir? (dir exists only when the
# upstream APK is installed and has run at least once; 'unavailable' otherwise).
EXTERNAL_WRITE="unavailable"
if adb_sh "test -d /sdcard/Android/data/$DEMO_PKG/files"; then
  probe="/sdcard/Android/data/$DEMO_PKG/files/.operator-stage0-probe"
  if adb_sh "echo probe > '$probe' && cat '$probe' && rm '$probe'" > "$RAW/app-external-probe.txt" 2>&1; then
    EXTERNAL_WRITE="ok"
  else
    EXTERNAL_WRITE="failed"
  fi
else
  printf 'dir absent (demo app not installed or has not run)\n' > "$RAW/app-external-probe.txt"
fi

# ------------------------------------------------------------------- bench sweep (02-M-5) ----
BENCH_RC=0
for t in $THREADS; do
  note "llama-bench: pp512/tg128, threads=$t, reps=$REPS (budget ${BENCH_TIMEOUT}s)"
  if timeout "$BENCH_TIMEOUT" \
      adb -s "$SERIAL" shell \
      "cd $REMOTE_DIR && LD_LIBRARY_PATH=$REMOTE_DIR ./llama-bench -m '$DEVICE_MODEL' -p 512 -n 128 -t $t -r $REPS -o json -oe md" \
      > "$RAW/bench-t${t}.json" 2> "$RAW/bench-t${t}.stderr.txt"; then
    note "threads=$t done"
  else
    rc=$?
    note "threads=$t FAILED (exit $rc); stderr tail follows"
    tail -n 5 "$RAW/bench-t${t}.stderr.txt" >&2 || true
    printf 'failed rc=%s\n' "$rc" > "$RAW/bench-t${t}.failed"
    BENCH_RC=1
  fi
done

# ------------------------------------------------------------------ metrics JSON ----
PY_RC=0
S0_SERIAL="$SERIAL" \
S0_CAPTURED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
S0_OUT="$OUT" \
S0_RAW="$RAW" \
S0_MODEL_ARG="$MODEL_ARG" \
S0_MODEL_SOURCE="$MODEL_SOURCE" \
S0_MODEL_DEVICE="$DEVICE_MODEL" \
S0_MODEL_SHA="$MODEL_SHA" \
S0_MODEL_BYTES="$MODEL_BYTES" \
S0_REPS="$REPS" \
S0_THREADS="$THREADS" \
S0_EXTERNAL_WRITE="$EXTERNAL_WRITE" \
S0_OPERATOR_COMMIT="$(git -C "$REPO_ROOT" rev-parse HEAD 2>/dev/null || printf null)" \
S0_LLAMA_COMMIT="$(git -C "$REPO_ROOT/third_party/llama.cpp" rev-parse HEAD 2>/dev/null || \
                   git -C "$REPO_ROOT" ls-tree HEAD third_party/llama.cpp 2>/dev/null | awk '{print $3}' || printf null)" \
S0_ADB_VERSION="$(adb --version | head -n1 || printf unknown)" \
python3 - <<'PY' || PY_RC=$?
import json, os, re, sys

raw = os.environ["S0_RAW"]
out_path = os.environ["S0_OUT"]

def read(name, default=""):
    try:
        with open(os.path.join(raw, name), encoding="utf-8", errors="replace") as f:
            return f.read()
    except OSError:
        return default

def read_lines(name):
    return [l for l in read(name).splitlines() if l.strip()]

def to_int(s):
    try:
        return int(s.strip())
    except (ValueError, AttributeError):
        return None

# getprop dump -> {key: value}
props = {}
for line in read("getprop.txt").splitlines():
    m = re.match(r"^\[([^\]]+)\]: \[(.*)\]$", line)
    if m:
        props[m.group(1)] = m.group(2)

# /proc/cpuinfo: features + per-core implementer/part
features = set()
implementers, parts = set(), set()
section = {}
for line in read("cpuinfo.txt").splitlines():
    if not line.strip():
        if "Features" in section:
            features.update(section["Features"].split())
            if "CPU implementer" in section:
                implementers.add(section["CPU implementer"].strip())
            if "CPU part" in section:
                parts.add(section["CPU part"].strip())
        section = {}
        continue
    if ":" in line:
        k, v = line.split(":", 1)
        section[k.strip()] = v.strip()
if "Features" in section:
    features.update(section["Features"].split())
    if "CPU implementer" in section:
        implementers.add(section["CPU implementer"].strip())
    if "CPU part" in section:
        parts.add(section["CPU part"].strip())

# /proc/meminfo
mem = {}
for line in read("meminfo.txt").splitlines():
    if ":" in line:
        k, v = line.split(":", 1)
        mem[k.strip()] = v.split()[0] if v.split() else None

# df -k /data (toybox: Filesystem 1K-blocks Used Available Use% Mounted on)
df_total = df_used = df_avail = None
for line in read("df-data.txt").splitlines():
    fields = line.split()
    if len(fields) >= 6 and fields[-1] == "/data":
        df_total, df_used, df_avail = to_int(fields[1]), to_int(fields[2]), to_int(fields[3])

def kb_from_bytes(s):
    n = to_int(s)
    return None if n is None else n // 1024

# public.libraries.txt visibility (02-M-2 / U3)
def listed(fname, lib):
    return (lib in read_lines(fname)) if read_lines(fname) else None
public = {
    "vendor": [listed("public-libraries-vendor.txt", "libOpenCL.so"),
               listed("public-libraries-vendor.txt", "libcdsprpc.so")],
    "system": [listed("public-libraries-system.txt", "libOpenCL.so"),
               listed("public-libraries-system.txt", "libcdsprpc.so")],
    "odm": [listed("public-libraries-odm.txt", "libOpenCL.so"),
            listed("public-libraries-odm.txt", "libcdsprpc.so")],
    "system_ext": [listed("public-libraries-system-ext.txt", "libOpenCL.so"),
                   listed("public-libraries-system-ext.txt", "libcdsprpc.so")],
}
libopencl_files = {}
for line in read_lines("libopencl-files.txt"):
    path, _, state = line.rpartition(" ")
    if path.startswith("/"):
        libopencl_files[path] = (state == "present")

# host sha256 of the pushed binaries
sha = {}
for line in read_lines("host-sha256.txt"):
    fields = line.split(None, 1)
    if len(fields) == 2:
        sha[os.path.basename(fields[1])] = fields[0]

# bench runs
threads = [int(t) for t in os.environ["S0_THREADS"].split()]
runs, cpu_variant = [], None
variant_re = re.compile(r"loaded cpu backend from .*?(libggml-cpu-[^\s'\"]+\.so)")
for t in threads:
    entry = {"threads": t, "status": "failed", "pp512_tokens_per_second": None,
             "tg128_tokens_per_second": None, "raw": f"bench-t{t}.json"}
    try:
        with open(os.path.join(raw, f"bench-t{t}.json"), encoding="utf-8", errors="replace") as f:
            rows = json.load(f)
        for row in rows:
            test, avg_ts = row.get("test"), row.get("avg_ts")
            if test == "pp512" and avg_ts is not None:
                entry["pp512_tokens_per_second"] = avg_ts
            elif test == "tg128" and avg_ts is not None:
                entry["tg128_tokens_per_second"] = avg_ts
        if entry["pp512_tokens_per_second"] is not None or entry["tg128_tokens_per_second"] is not None:
            entry["status"] = "ok"
    except (OSError, ValueError) as exc:
        entry["error"] = str(exc)
    stderr = read(f"bench-t{t}.stderr.txt")
    m = variant_re.search(stderr)
    if m:
        cpu_variant = m.group(1)
    runs.append(entry)

lmk_props = {k: v for k, v in props.items() if k.startswith("ro.lmk.")}

metrics = {
    "schema": "operator.stage0.metrics/1",
    "captured_at_utc": os.environ["S0_CAPTURED_AT"],
    "host": {
        "script": "tools/stage0/run.sh",
        "operator_commit": os.environ["S0_OPERATOR_COMMIT"],
        "llama_cpp_commit": os.environ["S0_LLAMA_COMMIT"],
        "adb_version": os.environ["S0_ADB_VERSION"],
    },
    "adb": {"serial": os.environ["S0_SERIAL"], "transport": "usb"},
    "device": {
        "abi": props.get("ro.product.cpu.abi"),
        "props": {
            "model": props.get("ro.product.model"),
            "brand": props.get("ro.product.brand"),
            "device": props.get("ro.product.device"),
            "build_display_id": props.get("ro.build.display.id"),
            "build_release": props.get("ro.build.version.release"),
            "build_sdk": props.get("ro.build.version.sdk"),
            "board_platform": props.get("ro.board.platform"),
            "hardware": props.get("ro.hardware"),
            "soc_manufacturer": props.get("ro.soc.manufacturer"),
            "soc_model": props.get("ro.soc.model"),
        },
        "cpu": {
            "nproc": to_int(read("nproc.txt")),
            "implementers": sorted(implementers),
            "parts": sorted(parts),
            "features": {f: (f in features) for f in ("asimddp", "i8mm", "bf16", "sve", "sme")},
        },
    },
    "memory": {
        "mem_total_kb": to_int(mem.get("MemTotal")),
        "mem_available_kb": to_int(mem.get("MemAvailable")),
        "zram": {
            "disksize_kb": kb_from_bytes(read("zram-disksize.txt")),
            "current_kb": kb_from_bytes(read("zram-size.txt")),
            "swaps_raw": read("swaps.txt").strip(),
        },
        "lmk_props": lmk_props,
    },
    "storage": {"data_total_kb": df_total, "data_used_kb": df_used, "data_avail_kb": df_avail},
    "opencl": {
        "listed_in_public_libraries": {
            part: {"libOpenCL_so": libs[0], "libcdsprpc_so": libs[1]}
            for part, libs in public.items()
        },
        "libopencl_files": libopencl_files,
    },
    "app_external_write": {
        "package": "com.example.llama.aichat",
        "status": os.environ["S0_EXTERNAL_WRITE"],
        "raw": "app-external-probe.txt",
    },
    "model": {
        "arg": os.environ["S0_MODEL_ARG"],
        "source": os.environ["S0_MODEL_SOURCE"],
        "device_path": os.environ["S0_MODEL_DEVICE"],
        "sha256": None if os.environ["S0_MODEL_SHA"] == "null" else os.environ["S0_MODEL_SHA"],
        "bytes": to_int(os.environ["S0_MODEL_BYTES"]),
    },
    "binaries": {"pushed": read_lines("pushed-files.txt"), "sha256": sha},
    "bench": {
        "reps": to_int(os.environ["S0_REPS"]),
        "tests": "pp512/tg128",
        "runs": runs,
        "cpu_variant_loaded": cpu_variant,
        "stderr_per_run": [f"bench-t{t}.stderr.txt" for t in threads],
    },
}

with open(out_path, "w", encoding="utf-8") as f:
    json.dump(metrics, f, indent=2, sort_keys=False)
    f.write("\n")

failed = [r["threads"] for r in runs if r["status"] != "ok"]
print(f"wrote {out_path}")
print(f"raw captures: {raw}/")
print(f"cpu_variant_loaded: {cpu_variant}")
if failed:
    print(f"bench runs failed for threads: {failed}", file=sys.stderr)
    sys.exit(1)
PY
PY_RC=$?

note "metrics: $OUT"
note "raw:     $RAW/"
exit "$(( BENCH_RC || PY_RC ))"

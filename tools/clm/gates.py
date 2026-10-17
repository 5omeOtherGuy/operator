#!/usr/bin/env python3
"""G0-G3 of the CLM fidelity test for one quantised encoder (FOUNDATION §5.4; 03§F5.2-F5.4).

The gates, in the order the design prescribes:

  **G0 token parity** — the HF tokenizer's ids (`add_special_tokens=False`, no chat template) against
  llama.cpp's (`POST /tokenize` with `add_special=false, parse_special=false`), for every corpus text.
  Both sides add no BOS and no EOS, so parity with `add_special=false` is exactly the "no BOS/EOS"
  half of the gate (Qwen3's vocab says `add_bos_token: false`, 03§F1.11). The bar is 100 %.

  **G1 tensor position** — the E0 reference state (`ref_encoder.py`: HF `last_hidden_state[:, -1]`, the
  post-final-RMSNorm tensor) against llama.cpp's state of the same text, read twice: the design's
  extraction path (`pooling=NONE`, the last row of the per-token list, i.e. `get_embeddings_ith(-1)`)
  and `pooling=LAST` (`get_embeddings_seq`). Both L2-normalised; bar `min cos >= 0.999` on the NONE
  path. A failure here means the wrong tensor is read, not a quantisation effect.

  **G2 raw embedding** — `cos(e_q, e_ref)` over the 4096-d L2-normalised vectors, reported for the
  state texts and for the candidate texts (count, mean, p5, median, min). The design sets no bar; the
  numbers are what the encoder decision is made on.

  **G3 projected/logit** — both sides through the CLM heads (the converted `clm-heads-v0.1.safetensors`
  and `fp32_ref.py`, the reference the Kotlin path mirrors): the cosine of the 512-d projections per
  head, and `|dlogit| = 100 * |cos_quant - cos_ref|` per (state, candidate) pair; median and p95, bar
  `median <= 0.5`, plus the top-1 agreement of the pair's candidates as a first signal for G4.

G4/G5 need labelled decisions (the decision set of lane 07/S12) and are not part of this tool.

Only summary numbers are printed: the corpus is screen state (§9.3 item 1) and the logs are kept as
CI artifacts.

Usage:
    python3 tools/clm/gates.py run --llama-bin DIR --model Qwen3-8B-Q8_0-outq2.gguf \\
        --corpus corpus.jsonl --reference ref.npz --heads clm-heads-v0.1.safetensors \\
        --out summary.json [--hf-tokenizer Qwen/Qwen3-8B] [--skip-g3]
"""

from __future__ import annotations

import argparse
import json
import math
import os
import signal
import socket
import statistics
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import fp32_ref as ref  # noqa: E402

DEFAULT_G1_MIN_COS = 0.999
DEFAULT_G3_MEDIAN_DLOGIT = 0.5
MAX_TEXT_TOKENS = 2048


# --- small helpers ------------------------------------------------------------------------------


def l2(vector) -> list[float]:
    """L2-normalise, in plain Python so the gate arithmetic reads like the spec."""
    total = 0.0
    for value in vector:
        total += value * value
    return [value / math.sqrt(total) for value in vector]


def cosine(a, b) -> float:
    total = 0.0
    for x, y in zip(a, b):
        total += x * y
    return total


def percentile(values: list[float], p: float) -> float:
    if not values:
        return float("nan")
    ordered = sorted(values)
    index = min(len(ordered) - 1, max(0, int(round((p / 100.0) * (len(ordered) - 1)))))
    return ordered[index]


def summarise(values: list[float]) -> dict:
    if not values:
        return {"count": 0}
    return {
        "count": len(values),
        "mean": statistics.fmean(values),
        "p5": percentile(values, 5),
        "median": statistics.median(values),
        "p95": percentile(values, 95),
        "min": min(values),
        "max": max(values),
    }


def argmax(values: list[float]) -> int:
    return max(range(len(values)), key=lambda i: values[i])


# --- llama.cpp server ---------------------------------------------------------------------------


class LlamaServer:
    """One `llama-server` process: the tokenizer (`/tokenize`) and the embedder (`/embeddings`)."""

    def __init__(self, binary: Path, model: Path, pooling: str, ctx: int, log: Path, port: int):
        self.binary = binary
        self.model = model
        self.pooling = pooling
        self.ctx = ctx
        self.log = log
        self.port = port
        self.process: subprocess.Popen | None = None
        self.handle = None

    def __enter__(self) -> "LlamaServer":
        command = [
            str(self.binary),
            "--model", str(self.model),
            "--embeddings",
            "--pooling", self.pooling,
            "--embd-normalize", "-1",
            "--ctx-size", str(self.ctx),
            "--parallel", "1",
            "--host", "127.0.0.1",
            "--port", str(self.port),
            "--no-webui",
        ]
        self.log.parent.mkdir(parents=True, exist_ok=True)
        self.handle = open(self.log, "ab")
        self.handle.write(f"# {' '.join(command)}\n".encode("utf-8"))
        self.handle.flush()
        self.process = subprocess.Popen(
            command, stdout=self.handle, stderr=subprocess.STDOUT, start_new_session=True
        )
        self._wait_ready()
        return self

    def __exit__(self, *exc) -> None:
        if self.process is not None and self.process.poll() is None:
            try:
                os.killpg(os.getpgid(self.process.pid), signal.SIGTERM)
            except ProcessLookupError:
                pass
            try:
                self.process.wait(timeout=60)
            except subprocess.TimeoutExpired:
                os.killpg(os.getpgid(self.process.pid), signal.SIGKILL)
                self.process.wait(timeout=30)
        if self.handle is not None:
            self.handle.close()

    def _post(self, path: str, payload: dict, timeout: float = 900.0):
        request = urllib.request.Request(
            f"http://127.0.0.1:{self.port}{path}",
            data=json.dumps(payload).encode("utf-8"),
            headers={"Content-Type": "application/json"},
        )
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return json.loads(response.read().decode("utf-8"))

    def _wait_ready(self, timeout_s: float = 1800.0) -> None:
        deadline = time.time() + timeout_s
        while time.time() < deadline:
            if self.process is not None and self.process.poll() is not None:
                raise SystemExit(f"llama-server exited with {self.process.returncode}; see {self.log}")
            if _port_open(self.port):
                try:
                    self._post("/health", {}, timeout=10.0)
                    print(f"llama-server ready (pooling={self.pooling}, port {self.port})", file=sys.stderr)
                    return
                except urllib.error.URLError:
                    pass
            time.sleep(2.0)
        raise SystemExit(f"llama-server was not ready within {timeout_s:.0f}s; see {self.log}")

    def tokenize(self, text: str) -> list[int]:
        """llama.cpp ids with `add_special=false, parse_special=false` (§5.4 G0)."""
        body = self._post("/tokenize", {"content": text, "add_special": False, "parse_special": False})
        return [int(token) for token in body["tokens"]]

    def embed(self, text: str) -> list[list[float]]:
        """The embedding rows of [text]: one per token for `pooling=none`, one pooled row for `last`."""
        body = self._post("/embeddings", {"content": text})
        entries = body if isinstance(body, list) else [body]
        rows: list[list[float]] = []
        for entry in entries:
            embedding = entry["embedding"]
            if embedding and isinstance(embedding[0], list):
                rows.extend(embedding)
            else:
                rows.append(embedding)
        return rows


def _port_open(port: int) -> bool:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.settimeout(1.0)
        return sock.connect_ex(("127.0.0.1", port)) == 0


# --- the gates ----------------------------------------------------------------------------------


def read_corpus(path: Path) -> list[dict]:
    lines = []
    with open(path, "r", encoding="utf-8") as handle:
        for raw in handle:
            raw = raw.strip()
            if raw:
                lines.append(json.loads(raw))
    return lines


def pairs_by_state(corpus: list[dict]) -> list[tuple[str, list[str]]]:
    """(state id, candidate ids) in corpus order; the corpus emits a state then its candidates."""
    pairs: list[tuple[str, list[str]]] = []
    for line in corpus:
        if line["role"] == "state":
            pairs.append((line["id"], []))
        elif pairs:
            pairs[-1][1].append(line["id"])
    return pairs


def gate0(hf_tokens: list[list[int]], llama_tokens: list[list[int]]) -> dict:
    mismatches = [i for i, (a, b) in enumerate(zip(hf_tokens, llama_tokens)) if a != b]
    lengths = [len(a) for a in hf_tokens]
    return {
        "texts": len(hf_tokens),
        "mismatches": len(mismatches),
        "parity": 1.0 - len(mismatches) / max(1, len(hf_tokens)),
        "tokens_total": sum(lengths),
        "tokens_max": max(lengths) if lengths else 0,
        "over_cap": sum(1 for length in lengths if length > MAX_TEXT_TOKENS),
        "pass": not mismatches and all(length <= MAX_TEXT_TOKENS for length in lengths),
    }


def gate1_g2(
    ids: list[str],
    roles: dict[str, str],
    reference: dict[str, list[float]],
    none_states: dict[str, list[float]],
    last_states: dict[str, list[float]],
) -> dict:
    """G1 (tensor position) and G2 (raw embedding cosine)."""
    none_cos: list[float] = []
    last_cos: list[float] = []
    state_cos: list[float] = []
    candidate_cos: list[float] = []
    for text_id in ids:
        expected = reference[text_id]
        none = none_states[text_id]
        last = last_states[text_id]
        if not (len(expected) == len(none) == len(last)):
            raise SystemExit(
                f"dimension mismatch on {text_id}: ref {len(expected)}, none {len(none)}, last {len(last)}"
            )
        unit = l2(expected)
        none_cos.append(cosine(unit, l2(none)))
        last_cos.append(cosine(unit, l2(last)))
        (state_cos if roles[text_id] == "state" else candidate_cos).append(none_cos[-1])
    return {
        "g1_none": summarise(none_cos),
        "g1_last": summarise(last_cos),
        "g2_state": summarise(state_cos),
        "g2_candidate": summarise(candidate_cos),
    }


def gate3(pairs: list[tuple[str, list[str]]], reference, quant, heads, scale: float) -> dict:
    """Projected cosine and |dlogit| for both sides through the same heads."""
    ops = ref.NumpyOps()
    reference_vectors = {text_id: ops.from_values(vector) for text_id, vector in reference.items()}
    quant_vectors = {text_id: ops.from_values(vector) for text_id, vector in quant.items()}
    reference_state = {text_id: ref.project_state(ops, heads, vector) for text_id, vector in reference_vectors.items()}
    reference_action = {text_id: ref.project_action(ops, heads, vector) for text_id, vector in reference_vectors.items()}
    quant_state = {text_id: ref.project_state(ops, heads, vector) for text_id, vector in quant_vectors.items()}
    quant_action = {text_id: ref.project_action(ops, heads, vector) for text_id, vector in quant_vectors.items()}

    dlogits: list[float] = []
    state_projection_cos: list[float] = []
    action_projection_cos: list[float] = []
    agreements = 0
    comparable = 0
    for state_id, candidate_ids in pairs:
        ref_scores = [scale * cosine(reference_state[state_id], reference_action[c]) for c in candidate_ids]
        quant_scores = [scale * cosine(quant_state[state_id], quant_action[c]) for c in candidate_ids]
        for a, b in zip(ref_scores, quant_scores):
            dlogits.append(abs(a - b))
        state_projection_cos.append(cosine(reference_state[state_id], quant_state[state_id]))
        for candidate_id in candidate_ids:
            action_projection_cos.append(cosine(reference_action[candidate_id], quant_action[candidate_id]))
        if len(candidate_ids) > 1:
            comparable += 1
            agreements += int(argmax(ref_scores) == argmax(quant_scores))
    return {
        "pairs": len(dlogits),
        "state_projection_cos": summarise(state_projection_cos),
        "action_projection_cos": summarise(action_projection_cos),
        "dlogit": summarise(dlogits),
        "top1_agreement": agreements / comparable if comparable else float("nan"),
        "top1_pairs": comparable,
    }


def load_reference(path: Path) -> tuple[list[str], dict[str, list[float]]]:
    import numpy as np  # noqa: PLC0415 - numpy is this tool's dependency

    data = np.load(path, allow_pickle=False)
    ids = [str(value) for value in data["ids"]]
    states = data["states"]
    return ids, {text_id: [float(x) for x in states[i]] for i, text_id in enumerate(ids)}


def run(args) -> dict:
    try:
        import numpy  # noqa: F401, PLC0415
    except ImportError as error:  # pragma: no cover - the CI job installs it
        raise SystemExit(f"gates.py needs numpy: {error}") from error

    corpus = read_corpus(Path(args.corpus))
    ids = [line["id"] for line in corpus]
    roles = {line["id"]: line["role"] for line in corpus}
    texts = [line["text"] for line in corpus]
    pairs = pairs_by_state(corpus)
    print(f"gates: {len(corpus)} texts, {len(pairs)} states, model {Path(args.model).name}", file=sys.stderr)

    from transformers import AutoTokenizer  # noqa: PLC0415 - the gates install it

    tokenizer = AutoTokenizer.from_pretrained(args.hf_tokenizer)
    hf_tokens = [list(tokenizer(text, add_special_tokens=False)["input_ids"]) for text in texts]

    server_binary = Path(args.llama_bin) / "llama-server"
    if not server_binary.is_file():
        raise SystemExit(f"{server_binary} is missing; build llama.cpp first")

    with LlamaServer(server_binary, Path(args.model), "none", args.ctx, Path(args.log), args.port) as server:
        llama_tokens = [server.tokenize(text) for text in texts]
        g0 = gate0(hf_tokens, llama_tokens)
        print(
            f"G0 token parity: {g0['parity'] * 100:.2f}% over {g0['texts']} texts "
            f"({g0['tokens_total']} tokens, {g0['mismatches']} mismatches, max {g0['tokens_max']})",
            file=sys.stderr,
        )
        if not g0["pass"] and not args.force:
            raise SystemExit("G0 failed: token parity must be 100%; the gates below would be meaningless")
        none_states = {text_id: server.embed(text)[-1] for text_id, text in zip(ids, texts)}

    with LlamaServer(server_binary, Path(args.model), "last", args.ctx, Path(args.log), args.port) as server:
        last_states = {text_id: server.embed(text)[-1] for text_id, text in zip(ids, texts)}

    reference_ids, reference = load_reference(Path(args.reference))
    present = [text_id for text_id in ids if text_id in reference]
    missing = [text_id for text_id in ids if text_id not in reference]
    if missing:
        print(f"gates: {len(missing)} corpus ids are not in the reference (sharded reference run?)", file=sys.stderr)
    if not present:
        raise SystemExit("the reference file holds none of the corpus ids")

    g1_g2 = gate1_g2(present, roles, reference, none_states, last_states)
    g1_g2["g1_none_pass"] = g1_g2["g1_none"]["min"] >= args.g1_min_cos
    g1_g2["g1_last_pass"] = g1_g2["g1_last"]["min"] >= args.g1_min_cos
    print(
        f"G1 tensor position: cos(NONE+ith) min {g1_g2['g1_none']['min']:.6f}, "
        f"cos(LAST) min {g1_g2['g1_last']['min']:.6f} (bar {args.g1_min_cos})",
        file=sys.stderr,
    )
    for key in ("g2_state", "g2_candidate"):
        row = g1_g2[key]
        print(
            f"G2 raw embedding {key[3:]}: mean {row['mean']:.6f}, p5 {row['p5']:.6f}, "
            f"min {row['min']:.6f} over {row['count']} texts",
            file=sys.stderr,
        )

    g3: dict = {"skipped": True}
    if not args.skip_g3:
        if not args.heads:
            raise SystemExit("--heads is required unless --skip-g3 is set")
        metadata, heads = ref.load_heads(str(args.heads))
        scale = ref.score_scale(float(metadata["logit_scale"]), float(metadata.get("scale_clamp", 100.0)))
        pairs_present = [
            (state_id, [c for c in candidate_ids if c in reference])
            for state_id, candidate_ids in pairs
            if state_id in reference
        ]
        pairs_present = [(state_id, candidate_ids) for state_id, candidate_ids in pairs_present if candidate_ids]
        g3 = gate3(pairs_present, reference, none_states, heads, scale)
        g3["median_pass"] = g3["dlogit"]["median"] <= args.g3_median_dlogit
        print(
            f"G3 projected/logit: |dlogit| median {g3['dlogit']['median']:.4f}, p95 {g3['dlogit']['p95']:.4f} "
            f"(bar {args.g3_median_dlogit}); state cos min {g3['state_projection_cos']['min']:.6f}, "
            f"top-1 agreement {g3['top1_agreement'] * 100:.2f}% over {g3['top1_pairs']} pairs",
            file=sys.stderr,
        )

    summary = {
        "model": str(args.model),
        "corpus": str(args.corpus),
        "reference": str(args.reference),
        "heads": str(args.heads) if args.heads else None,
        "texts": len(corpus),
        "reference_texts": len(reference_ids),
        "bars": {"g1_min_cos": args.g1_min_cos, "g3_median_dlogit": args.g3_median_dlogit},
        "g0": g0,
        "g1_g2": g1_g2,
        "g3": g3,
    }
    summary["pass"] = bool(g0["pass"] and g1_g2["g1_none_pass"] and (g3.get("skipped") or g3["median_pass"]))
    return summary


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    run_parser = sub.add_parser("run", help="run G0-G3 for one model")
    run_parser.add_argument("--llama-bin", required=True, help="the cmake build directory holding llama-server")
    run_parser.add_argument("--model", required=True, help="the GGUF under test")
    run_parser.add_argument("--corpus", required=True)
    run_parser.add_argument("--reference", required=True, help="ref.npz from tools/clm/ref_encoder.py")
    run_parser.add_argument("--heads", help="clm-heads-v0.1.safetensors (G3); omit with --skip-g3")
    run_parser.add_argument("--out", help="where to write the summary JSON")
    run_parser.add_argument("--hf-tokenizer", default="Qwen/Qwen3-8B")
    run_parser.add_argument("--ctx", type=int, default=4096)
    run_parser.add_argument("--port", type=int, default=8080)
    run_parser.add_argument("--log", default="build/clm-gates/llama-server.log")
    run_parser.add_argument("--skip-g3", action="store_true")
    run_parser.add_argument("--force", action="store_true", help="continue after a G0 failure (report only)")
    run_parser.add_argument("--g1-min-cos", type=float, default=DEFAULT_G1_MIN_COS)
    run_parser.add_argument("--g3-median-dlogit", type=float, default=DEFAULT_G3_MEDIAN_DLOGIT)
    args = parser.parse_args()

    summary = run(args)
    if args.out:
        Path(args.out).parent.mkdir(parents=True, exist_ok=True)
        Path(args.out).write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({k: summary[k] for k in ("pass", "g0", "g1_g2", "g3")}, indent=2))
    return 0 if summary["pass"] else 1


if __name__ == "__main__":
    raise SystemExit(main())

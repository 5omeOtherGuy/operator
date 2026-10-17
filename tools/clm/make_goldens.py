#!/usr/bin/env python3
"""Writes the JVM golden fixtures for the Kotlin CLM-8B head path (FOUNDATION §5.3-5.4; ADR-0007).

Two files, both under `agent-core/src/test/resources/clm/`:

  * `schema_goldens.json` — the port of CLM's `schema.py`: state and candidate texts plus the token
    sequences of the injected tokenizer, produced by an independent Python implementation
    (`schema_ref.py`) and pinned by `ClmSchemaTest`;
  * `head_goldens.json` — the fp32 head reference: the inputs, the 512-d projections of both heads and
    the scaled cosine scores, produced by `fp32_ref.py` and pinned by `ClmHeadsGoldenTest`.

The head weights are **not** committed: the two heads are 18,887,680 fp32 values (75.5 MB), over the
30 MB budget of the test resources. The file carries the generator instead — seed 0, splitmix64, the
tensor order and the per-tensor scale — and both sides rebuild the identical weights: this script here,
and the JVM tests' `ClmWeights` helper. `weights_sha256` pins that equality before any number is
compared, so a generator that drifts fails loudly instead of producing confusing score mismatches.

Usage:
    python3 tools/clm/make_goldens.py                 # write the fixtures (numpy when importable)
    python3 tools/clm/make_goldens.py --backend pure  # the pure-Python fp32 backend
    python3 tools/clm/make_goldens.py --backend numpy # the numpy fp32 backend
    python3 tools/clm/make_goldens.py --check         # regenerate and diff against the committed files

`--check` runs with `--backend pure` as well in CI, and the two backends must agree byte for byte
(`fp32_ref.PureOps` rounds after every operation, `NumpyOps` accumulates sequentially in float32).
"""

from __future__ import annotations

import argparse
import json
import struct
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import fp32_ref as ref  # noqa: E402
import schema_ref as schema  # noqa: E402

REPO_ROOT = Path(__file__).resolve().parents[2]
RESOURCE_DIR = REPO_ROOT / "agent-core" / "src" / "test" / "resources" / "clm"

# The schema cases: prose contexts like the ones lane 04 renders (§6), the four question kinds, a
# unicode context and two over-long texts that exercise the tail/head rule.
SCHEMA_CASES = [
    {
        "name": "yesno-battery-saver",
        "kind": "yesno",
        "context": (
            "Screen: Settings > Battery.\n"
            "A switch labelled \"Battery saver\" is off. The battery is at 42%.\n"
            "Buttons: \"Battery saver\", \"Adaptive preferences\"."
        ),
        "instructions": "Is the battery saver switch off?",
        "max_tokens": 12,
    },
    {
        "name": "choice-wifi-row",
        "kind": "choice",
        "context": (
            "Screen: Settings.\nRows: \"Wi-Fi\", \"Bluetooth\", \"Hotspot\".\n"
            "The row \"Bluetooth\" is highlighted."
        ),
        "instructions": "Which row opens the Wi-Fi screen?",
        "options": {"a": "Wi-Fi", "b": "Bluetooth", "c": ""},
        "max_tokens": 12,
    },
    {
        "name": "score-message-sent",
        "kind": "score",
        "context": (
            "Screen: Messages > Alice.\n"
            "The input field holds \"See you at 6\".\nThe message list is unchanged."
        ),
        "instructions": "Did the message send?",
        "levels": ["No, it is still a draft", "Unclear", "Yes, it is sent"],
        "max_tokens": 12,
    },
    {
        "name": "rank-next-step",
        "kind": "rank",
        "context": (
            "Screen: Messages > Alice.\n"
            "The input field holds \"See you at 6\".\nButtons: \"Send\", \"Attach\"."
        ),
        "instructions": "Rank the next steps.",
        "candidates": ["Tap Send", "Tap Attach", "Close the app"],
        "max_tokens": 12,
    },
    {
        "name": "unicode-context",
        "kind": "yesno",
        "context": "Bildschirm: Einstellungen. Der Knopf \u201eSpeichern\u201c ist aktiv.\nZweite Zeile: 100 % geladen.",
        "instructions": "Ist \u201eSpeichern\u201c aktiv?",
        "max_tokens": 40,
    },
    {
        "name": "long-context-tail",
        "kind": "choice",
        "context": "Screen: a long list. " + " ".join(f"row {i}" for i in range(200)),
        "instructions": "Which row is highlighted?",
        "options": {"a": "row 3", "b": "row 199"},
        "max_tokens": 40,
    },
]

# The inputs of the head goldens: three states and four candidates, plus the raw scale each one is
# drawn with (the head path L2-normalises, so the scale only changes the last bits — which is exactly
# what the golden test should see).
STATE_SCALES = [60.0, 120.0, 500.0]
CANDIDATE_SCALES = [30.0, 45.0, 60.0, 90.0]

# The same, for the golden built from the published checkpoint (`--real-heads`).
REAL_SEED = 7
REAL_INPUT_SCALES = [90.0, 300.0]
REAL_CANDIDATE_SCALES = [40.0, 70.0]


def schema_goldens() -> dict:
    cases = []
    for case in SCHEMA_CASES:
        kind = case["kind"]
        instructions = case["instructions"]
        state_text = schema.state_text(case["context"], instructions)
        candidate_texts = schema.candidate_texts(
            kind,
            instructions,
            options=case.get("options"),
            levels=case.get("levels"),
            candidates=case.get("candidates"),
        )
        max_tokens = case["max_tokens"]
        entry = {
            "name": case["name"],
            "kind": kind,
            "context": case["context"],
            "instructions": instructions,
            "max_tokens": max_tokens,
            "state_text": state_text,
            "candidate_texts": candidate_texts,
            "state_tokens": schema.keep_tail(schema.byte_tokenize(state_text), max_tokens),
            "candidate_tokens": [
                schema.keep_head(schema.byte_tokenize(text), max_tokens) for text in candidate_texts
            ],
        }
        for key in ("options", "levels", "candidates"):
            if key in case:
                entry[key] = case[key]
        cases.append(entry)
    return {
        "recipe_version": schema.RECIPE_VERSION,
        "max_tokens": schema.MAX_TOKENS,
        "tokenizer": "byte (one token per UTF-8 byte); the phone injects LlmPort.tokenize(addSpecial=false, parseSpecial=false)",
        "state_text": "context + \"\\n\\n\" + instructions",
        "cases": cases,
    }


def head_goldens(ops, stream) -> dict:
    heads = ref.build_weights(ops, stream)
    digest = ref.weights_sha256(ops, heads)
    raw_states = [
        ops.scale(ops.uniform(stream, ref.INPUT_DIM), scale) for scale in STATE_SCALES
    ]
    raw_candidates = [
        ops.scale(ops.uniform(stream, ref.INPUT_DIM), scale) for scale in CANDIDATE_SCALES
    ]
    state_vectors = [ref.project_state(ops, heads, raw) for raw in raw_states]
    action_vectors = [ref.project_action(ops, heads, raw) for raw in raw_candidates]
    scale = ref.score_scale()
    scores = [
        [ref.score(ops, state_vector, action_vector, scale) for action_vector in action_vectors]
        for state_vector in state_vectors
    ]
    return {
        "recipe_version": ref.RECIPE_VERSION,
        "generator": {
            "prng": "splitmix64",
            "seed": ref.SEED,
            "draw": "((z >>> 40) & 0xFFFFFF) / 2**23 - 1  (in [-1, 1), exact in fp32)",
            "stream_order": [
                f"{head}.{name}" for head in ref.HEAD_ORDER for name, _shape, _kind in ref.TENSOR_SPEC
            ],
            "tensor_kind": {
                f"{name}": kind for name, _shape, kind in ref.TENSOR_SPEC
            },
            "value": {
                "w": "r32(u * 1.0)",
                "b": "r32(u * 0.05)",
                "g": "r32(1.0 + r32(u * 0.1))",
            },
            "input_scales": STATE_SCALES,
            "candidate_scales": CANDIDATE_SCALES,
            "note": "r32() rounds to binary32; the JVM tests' ClmWeights helper rebuilds the same stream",
        },
        "weights_sha256": digest,
        "shapes": {
            f"{head}.{name}": list(shape)
            for head in ref.HEAD_ORDER
            for name, shape, _kind in ref.TENSOR_SPEC
        },
        "logit_scale": ref.LOGIT_SCALE,
        "scale": scale,
        "ln_eps": ref.LN_EPS,
        "inputs": [ops.to_list(raw) for raw in raw_states],
        "candidates": [ops.to_list(raw) for raw in raw_candidates],
        "expected_state_vectors": [ops.to_list(vector) for vector in state_vectors],
        "expected_action_vectors": [ops.to_list(vector) for vector in action_vectors],
        "expected_scores": [[float(value) for value in row] for row in scores],
    }


def real_heads_goldens(ops, source: Path) -> dict:
    """The golden of the **published** heads: the checkpoint's own weights, projected by this reference.

    `tools/clm/convert_heads.py` turns the pinned `CLM_v0.1-8B.pt` into safetensors; the JVM test
    `ClmHeadsRealFileTest` reads that file with the Kotlin loader and compares against this golden. It
    runs only when `CLM_HEADS_FILE` and `CLM_HEADS_GOLDEN` are set (the `heads` job of `clm-gates.yml`),
    because the file is 75.5 MB and never committed.
    """
    import convert_heads as converter

    obj = converter.read_checkpoint(source)
    heads = {}
    for head in ref.HEAD_ORDER:
        state = obj[head]
        fields = {}
        for name, _shape, _kind in ref.TENSOR_SPEC:
            tensor = state[name]
            raw = tensor.storage.float32()
            start = tensor.offset * 4
            payload = raw[start:start + tensor.numel * 4]
            fields[name] = ops.from_values(struct.unpack(f"<{tensor.numel}f", payload))
        heads[head] = ref.HeadWeights(
            inp_w=fields["inp.weight"],
            inp_b=fields["inp.bias"],
            hidden_w=fields["hidden.0.weight"],
            hidden_b=fields["hidden.0.bias"],
            norm_w=fields["norms.0.weight"],
            norm_b=fields["norms.0.bias"],
            out_w=fields["out.weight"],
            out_b=fields["out.bias"],
        )
    logit_scale = converter.scalar_of(obj["logit_scale"])

    stream = ops.stream(REAL_SEED)
    raw_states = [ops.scale(ops.uniform(stream, ref.INPUT_DIM), scale) for scale in REAL_INPUT_SCALES]
    raw_candidates = [ops.scale(ops.uniform(stream, ref.INPUT_DIM), scale) for scale in REAL_CANDIDATE_SCALES]
    state_vectors = [ref.project_state(ops, heads, raw) for raw in raw_states]
    action_vectors = [ref.project_action(ops, heads, raw) for raw in raw_candidates]
    scale = ref.score_scale(logit_scale)
    scores = [
        [ref.score(ops, state_vector, action_vector, scale) for action_vector in action_vectors]
        for state_vector in state_vectors
    ]
    return {
        "recipe_version": ref.RECIPE_VERSION,
        "source": f"{converter.SOURCE_REPO}/{converter.SOURCE_FILE}",
        "source_sha256": converter.SOURCE_SHA256,
        "logit_scale": logit_scale,
        "scale": scale,
        "seed": REAL_SEED,
        "input_scales": REAL_INPUT_SCALES,
        "candidate_scales": REAL_CANDIDATE_SCALES,
        "inputs": [ops.to_list(raw) for raw in raw_states],
        "candidates": [ops.to_list(raw) for raw in raw_candidates],
        "expected_state_vectors": [ops.to_list(vector) for vector in state_vectors],
        "expected_action_vectors": [ops.to_list(vector) for vector in action_vectors],
        "expected_scores": [[float(value) for value in row] for row in scores],
    }


def dump(path: Path, payload: dict) -> None:
    text = json.dumps(payload, indent=1, ensure_ascii=False)
    path.write_text(text + "\n", encoding="utf-8")


def write_all(out_dir: Path, backend: str) -> dict[str, str]:
    ops, stream = ref.make_ops(backend)
    files = {
        "schema_goldens.json": schema_goldens(),
        "head_goldens.json": head_goldens(ops, stream),
    }
    out_dir.mkdir(parents=True, exist_ok=True)
    written = {}
    for name, payload in files.items():
        path = out_dir / name
        dump(path, payload)
        written[name] = str(path)
    return written


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--backend", choices=["auto", "pure", "numpy"], default="auto")
    parser.add_argument("--out-dir", default=str(RESOURCE_DIR), help="where the fixtures are written")
    parser.add_argument(
        "--real-heads",
        metavar="CLM_v0.1-8B.pt",
        help="write the published-heads golden of that checkpoint instead of the committed fixtures",
    )
    parser.add_argument("--out", help="output file for --real-heads")
    parser.add_argument(
        "--check",
        action="store_true",
        help="regenerate into a temp dir and compare with the committed files; non-zero on a diff",
    )
    args = parser.parse_args()

    if args.real_heads:
        out = Path(args.out) if args.out else Path("clm-heads-golden.json")
        ops, _ = ref.make_ops(args.backend if args.backend != "auto" else "pure")
        payload = real_heads_goldens(ops, Path(args.real_heads))
        dump(out, payload)
        print(
            f"wrote {out} ({out.stat().st_size} bytes): {len(payload['inputs'])} states, "
            f"{len(payload['candidates'])} candidates, logit_scale {payload['logit_scale']:.6f}, "
            f"scale {payload['scale']:.3f}"
        )
        return 0

    if args.check:
        import tempfile

        backend = args.backend if args.backend != "auto" else "pure"
        with tempfile.TemporaryDirectory() as tmp:
            written = write_all(Path(tmp), backend)
            status = 0
            for name, path in written.items():
                regenerated = Path(path).read_bytes()
                committed = (Path(args.out_dir) / name)
                if not committed.is_file():
                    print(f"{name}: MISSING at {committed}")
                    status = 1
                    continue
                if regenerated != committed.read_bytes():
                    print(f"{name}: DIFFERS from {committed} (backend {backend})")
                    status = 1
                else:
                    print(f"{name}: identical ({len(regenerated)} bytes, backend {backend})")
            return status

    written = write_all(Path(args.out_dir), args.backend)
    for name, path in written.items():
        print(f"wrote {path} ({Path(path).stat().st_size} bytes)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

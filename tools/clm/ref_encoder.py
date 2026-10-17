#!/usr/bin/env python3
"""Layer-streamed bf16 reference encoder for dense Qwen3 models — the E0 candidate of FOUNDATION §5.4.

Safetensors are parsed directly: an eight-byte little-endian header length is
followed by JSON and little-endian tensor bytes whose offsets are relative to
the data section.  Weight files are memory-mapped one at a time and removed
before the next is fetched, so a 16 GB checkpoint can be run on a 16 GB runner
(one shard, plus the activations of the whole corpus, is resident).

Transformers' CPU bf16 path is emulated with float32 NumPy arrays containing
exact bf16 values.  ``to_bf16`` performs round-to-nearest-even; every value
which PyTorch materialises as bf16 (norm, linear, rotary, softmax, products and
residuals) is rounded, while matrix products accumulate in fp32.  The result is
the unnormalised, post-final-RMSNorm last-token state (`last_hidden_state[:, -1]`),
exported as fp32 — the tensor 03§F1.10 identifies as the state the CLM heads read.

Ids come from HF's own tokenizer (`transformers.AutoTokenizer`, no chat template,
`add_special_tokens=False`), exactly the ids G0 compares with llama.cpp's.  The whole
pipeline (this emulation, the quantised encoder, the gates) is what `--hf-cross-check`
validates against transformers + torch on the smoke pair.

    python3 tools/clm/ref_encoder.py --selftest                      # no network, tiny model
    python3 tools/clm/ref_encoder.py --model Qwen/Qwen3-8B --corpus corpus.jsonl \\
        --out ref.npz --shard 0 --shards 4 --cache-dir build/clm-cache
"""

from __future__ import annotations

import argparse
import json
import math
import os
import shutil
import struct
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any

import numpy as np

HF_ROOT = "https://huggingface.co"
LAYER_KEYS = (
    "input_layernorm.weight", "self_attn.q_proj.weight", "self_attn.k_proj.weight",
    "self_attn.v_proj.weight", "self_attn.q_norm.weight", "self_attn.k_norm.weight",
    "self_attn.o_proj.weight", "post_attention_layernorm.weight",
    "mlp.gate_proj.weight", "mlp.up_proj.weight", "mlp.down_proj.weight",
)


def to_bf16(x: Any) -> np.ndarray:
    """Round float32 to bf16, ties-to-even, returning float32 exact values."""
    a = np.asarray(x, dtype=np.float32)
    u = a.view(np.uint32)
    # NaNs must remain NaNs rather than occasionally rounding to infinity.
    rounded = u + np.uint32(0x7FFF) + ((u >> np.uint32(16)) & np.uint32(1))
    rounded = rounded & np.uint32(0xFFFF0000)
    nan = ((u & np.uint32(0x7F800000)) == np.uint32(0x7F800000)) & (
        (u & np.uint32(0x007FFFFF)) != 0
    )
    rounded = np.where(nan, u | np.uint32(0x00400000), rounded).astype(np.uint32)
    return rounded.view(np.float32)


def read_safetensors_header(path: Path) -> tuple[dict[str, Any], int]:
    with path.open("rb") as f:
        raw = f.read(8)
        if len(raw) != 8:
            raise ValueError(f"truncated safetensors header: {path}")
        length = struct.unpack("<Q", raw)[0]
        encoded = f.read(length)
        if len(encoded) != length:
            raise ValueError(f"truncated safetensors JSON header: {path}")
        header = json.loads(encoded)
        if not isinstance(header, dict):
            raise ValueError(f"invalid safetensors header: {path}")
    return header, 8 + length


def load_tensor(path: Path, name: str, header: dict[str, Any] | None = None,
                data_start: int | None = None) -> np.ndarray:
    """Return a read-only mapped tensor as float32 (BF16 is expanded)."""
    if header is None or data_start is None:
        header, data_start = read_safetensors_header(path)
    if name not in header:
        raise KeyError(f"tensor {name!r} is absent from {path.name}")
    spec = header[name]
    begin, end = map(int, spec["data_offsets"])
    shape = tuple(map(int, spec["shape"]))
    dtype = spec["dtype"]
    types = {"F32": "<f4", "F16": "<f2", "BF16": "<u2"}
    if dtype not in types:
        raise ValueError(f"unsupported safetensors dtype {dtype} for {name}")
    item = np.dtype(types[dtype]).itemsize
    if end - begin != math.prod(shape) * item:
        raise ValueError(f"bad byte count for tensor {name}")
    raw = np.memmap(path, mode="r", dtype=types[dtype], offset=data_start + begin,
                    shape=shape, order="C")
    if dtype == "BF16":
        return (np.asarray(raw, dtype=np.uint32) << np.uint32(16)).view(np.float32)
    return np.asarray(raw, dtype=np.float32)


def rms_norm(x: np.ndarray, weight: np.ndarray, eps: float) -> np.ndarray:
    variance = np.mean(x.astype(np.float32) ** np.float32(2.0), axis=-1, keepdims=True,
                       dtype=np.float32)
    y = x * (np.float32(1.0) / np.sqrt(variance + np.float32(eps)))
    return to_bf16(y * weight)


def linear(x: np.ndarray, weight: np.ndarray) -> np.ndarray:
    return to_bf16(x.astype(np.float32) @ weight.T.astype(np.float32))


def apply_rope(q: np.ndarray, k: np.ndarray, theta: float) -> tuple[np.ndarray, np.ndarray]:
    """Apply Qwen rotate-half RoPE to [T, heads, head_dim] tensors."""
    dim = q.shape[-1]
    if dim % 2:
        raise ValueError("head_dim must be even for RoPE")
    half = dim // 2
    inv = np.float32(theta) ** (-np.arange(half, dtype=np.float32) * np.float32(2.0 / dim))
    angles = np.arange(q.shape[0], dtype=np.float32)[:, None] * inv[None, :]
    cos, sin = np.cos(angles).astype(np.float32)[:, None, :], np.sin(angles).astype(np.float32)[:, None, :]

    def rotate(x: np.ndarray) -> np.ndarray:
        a, b = x[..., :half], x[..., half:]
        return to_bf16(np.concatenate((a * cos - b * sin, b * cos + a * sin), axis=-1))

    return rotate(q), rotate(k)


def attention(q: np.ndarray, k: np.ndarray, v: np.ndarray) -> np.ndarray:
    """Causal GQA attention; inputs are [T, heads, D], output [T, q_heads*D]."""
    t, nq, dim = q.shape
    nk = k.shape[1]
    if nq % nk:
        raise ValueError("num_attention_heads must be divisible by num_key_value_heads")
    group = nq // nk
    mask = np.triu(np.ones((t, t), dtype=bool), k=1)
    outputs = []
    scale = np.float32(1.0 / math.sqrt(dim))
    for head in range(nq):
        kv = head // group
        scores = to_bf16(q[:, head] @ k[:, kv].T)
        scores = to_bf16(scores * scale)
        scores = np.where(mask, np.float32(-np.inf), scores)
        maximum = np.max(scores, axis=-1, keepdims=True)
        exp = np.exp(scores - maximum).astype(np.float32)
        probs = to_bf16(exp / np.sum(exp, axis=-1, keepdims=True, dtype=np.float32))
        outputs.append(to_bf16(probs @ v[:, kv]))
    return np.stack(outputs, axis=1).reshape(t, nq * dim)


def layer_forward(h: np.ndarray, weights: dict[str, np.ndarray], cfg: dict[str, Any]) -> np.ndarray:
    eps = float(cfg["rms_norm_eps"])
    nq, nk = int(cfg["num_attention_heads"]), int(cfg["num_key_value_heads"])
    dim = int(cfg["head_dim"])
    r = rms_norm(h, weights["input_layernorm.weight"], eps)
    q = linear(r, weights["self_attn.q_proj.weight"]).reshape(len(h), nq, dim)
    k = linear(r, weights["self_attn.k_proj.weight"]).reshape(len(h), nk, dim)
    v = linear(r, weights["self_attn.v_proj.weight"]).reshape(len(h), nk, dim)
    q = rms_norm(q, weights["self_attn.q_norm.weight"], eps)
    k = rms_norm(k, weights["self_attn.k_norm.weight"], eps)
    q, k = apply_rope(q, k, float(cfg["rope_theta"]))
    a = linear(attention(q, k, v), weights["self_attn.o_proj.weight"])
    h = to_bf16(h + a)
    r = rms_norm(h, weights["post_attention_layernorm.weight"], eps)
    gate = linear(r, weights["mlp.gate_proj.weight"])
    # torch.nn.functional.silu returns a bf16 tensor, as does the following multiply.
    silu = to_bf16(gate / (np.float32(1.0) + np.exp(-gate).astype(np.float32)))
    mixed = to_bf16(silu * linear(r, weights["mlp.up_proj.weight"]))
    return to_bf16(h + linear(mixed, weights["mlp.down_proj.weight"]))


def _download(url: str, destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_name(destination.name + ".part")
    try:
        with urllib.request.urlopen(url) as response, temporary.open("wb") as out:
            shutil.copyfileobj(response, out, length=1024 * 1024)
        os.replace(temporary, destination)
    except (OSError, urllib.error.URLError) as exc:
        temporary.unlink(missing_ok=True)
        raise RuntimeError(f"download failed: {url}: {exc}") from exc


def _metadata_file(model: str, name: str, cache: Path) -> Path:
    if Path(model).is_dir():
        path = Path(model) / name
        if not path.is_file():
            raise FileNotFoundError(f"missing {name} in {model}")
        return path
    path = cache / "metadata" / model.replace("/", "--") / name
    if not path.exists():
        quoted = urllib.parse.quote(model, safe="/")
        _download(f"{HF_ROOT}/{quoted}/resolve/main/{urllib.parse.quote(name)}", path)
    return path


def _remote_header(url: str) -> dict[str, Any]:
    """The safetensors header of a remote file, over range requests (never the whole file)."""
    def get(rng: str) -> bytes:
        request = urllib.request.Request(url, headers={"Range": f"bytes={rng}"})
        with urllib.request.urlopen(request) as response:
            return response.read()

    raw = get("0-7")
    if len(raw) != 8:
        raise RuntimeError(f"could not read the safetensors header length of {url}")
    length = struct.unpack("<Q", raw)[0]
    encoded = get(f"8-{8 + length - 1}")
    if len(encoded) != length:
        raise RuntimeError(f"truncated safetensors header from {url}")
    return json.loads(encoded)


def _single_file_weight_map(header: dict[str, Any]) -> dict[str, str]:
    return {name: "model.safetensors" for name in header if name != "__metadata__"}


def _weight_map(model: str, cache: Path) -> dict[str, str]:
    """The checkpoint's `weight_map`: its index, or the single file's own header.

    Qwen3-0.6B (the smoke pair) is published as one `model.safetensors` without an index; for those
    checkpoints the tensor names come from the header — read over HTTP range requests when the model
    is remote, so that planning the stream never downloads 15 GB of weights.
    """
    local = Path(model).is_dir()
    if local:
        index = Path(model) / "model.safetensors.index.json"
        if index.is_file():
            return json.loads(index.read_text())["weight_map"]
        return _single_file_weight_map(read_safetensors_header(Path(model) / "model.safetensors")[0])
    try:
        return json.loads(_metadata_file(model, "model.safetensors.index.json", cache).read_text())["weight_map"]
    except RuntimeError:
        url = f"{HF_ROOT}/{urllib.parse.quote(model, safe='/')}/resolve/main/model.safetensors"
        return _single_file_weight_map(_remote_header(url))


def _revision(model: str) -> str:
    if Path(model).is_dir():
        return "selftest-local"
    url = f"{HF_ROOT}/api/models/{urllib.parse.quote(model, safe='/')}"
    try:
        with urllib.request.urlopen(url) as response:
            return str(json.load(response)["sha"])
    except (OSError, urllib.error.URLError, KeyError, json.JSONDecodeError) as exc:
        raise RuntimeError(f"could not resolve model revision: {exc}") from exc


# The tokenizer is HF's own (`transformers.AutoTokenizer`, never a chat template): G0 compares its ids
# with llama.cpp's, and the reference states must be computed on exactly the ids the gates compare.
# `tokenizer.json` is fetched (or read from `--model` when it is a local directory) by transformers.
def _stage_specs(layers: int) -> list[tuple[str, list[str]]]:
    stages = [("embedding", ["model.embed_tokens.weight"])]
    for i in range(layers):
        stages.append((f"layer {i + 1}/{layers}", [f"model.layers.{i}.{key}" for key in LAYER_KEYS]))
    stages.append(("final norm", ["model.norm.weight"]))
    return stages


def encode_texts(token_ids: list[list[int]], cfg: dict[str, Any], index: dict[str, Any],
                 model: str, cache: Path) -> np.ndarray:
    """Run all texts through each layer while visiting every weight shard once."""
    weight_map = index["weight_map"]
    stages = _stage_specs(int(cfg["num_hidden_layers"]))
    required = [name for _, names in stages for name in names]
    missing = [name for name in required if name not in weight_map]
    if missing:
        raise ValueError(f"weight index is missing {missing[0]}")
    shard_order = sorted({weight_map[name] for name in required})
    positions = {name: i for i, name in enumerate(shard_order)}
    # Consecutive layers may share a boundary shard, but may not return to an older one.
    previous_min = previous_max = 0
    for _, names in stages:
        occupied = [positions[weight_map[name]] for name in names]
        if min(occupied) < previous_min or max(occupied) < previous_max:
            raise ValueError("non-monotonic Qwen weight_map cannot be streamed one shard at a time")
        previous_min, previous_max = min(occupied), max(occupied)

    if not token_ids:
        print("texts done", file=sys.stderr, flush=True)
        return np.empty((0, int(cfg["hidden_size"])), dtype=np.float32)
    local = Path(model).is_dir()
    activations: list[np.ndarray] = []
    pending: dict[str, np.ndarray] = {}
    stage_at = 0
    for shard_no, shard in enumerate(shard_order, 1):
        print(f"shard {shard_no}/{len(shard_order)}", file=sys.stderr, flush=True)
        path = Path(model) / shard if local else cache / shard
        if not local:
            # No stale weight file survives into this download.
            for old in cache.glob("*.safetensors"):
                old.unlink()
            quoted = urllib.parse.quote(model, safe="/")
            _download(f"{HF_ROOT}/{quoted}/resolve/main/{urllib.parse.quote(shard)}", path)
        try:
            header, start = read_safetensors_header(path)
            while stage_at < len(stages):
                label, names = stages[stage_at]
                here = [name for name in names if weight_map[name] == shard]
                future = [name for name in names if positions[weight_map[name]] > shard_no - 1]
                for name in here:
                    pending[name] = load_tensor(path, name, header, start)
                if future:
                    # Mapped arrays cannot outlive deletion of this shard portably.
                    pending = {name: np.array(value, dtype=np.float32, copy=True)
                               for name, value in pending.items()}
                    break
                if any(name not in pending for name in names):
                    raise ValueError(f"streaming order did not provide all tensors for {label}")
                if label == "embedding":
                    table = pending[names[0]]
                    activations = [to_bf16(table[np.asarray(ids, dtype=np.int64)]) for ids in token_ids]
                elif label == "final norm":
                    norm = pending[names[0]]
                    activations = [rms_norm(h, norm, float(cfg["rms_norm_eps"])) for h in activations]
                else:
                    print(label, file=sys.stderr, flush=True)
                    prefix = names[0].rsplit(".", 1)[0].split("input_layernorm")[0]
                    weights = {name[len(prefix):]: pending[name] for name in names}
                    activations = [layer_forward(h, weights, cfg) for h in activations]
                for name in names:
                    pending.pop(name, None)
                stage_at += 1
        finally:
            if not local:
                path.unlink(missing_ok=True)
    if stage_at != len(stages) or pending:
        raise ValueError("weight stream ended before the model was complete")
    print("texts done", file=sys.stderr, flush=True)
    return np.stack([h[-1].astype(np.float32) for h in activations])


def _read_corpus(path: Path, shard: int, shards: int) -> tuple[list[str], list[str]]:
    ids, texts = [], []
    with path.open(encoding="utf-8") as f:
        for line_no, line in enumerate(f, 1):
            try:
                item = json.loads(line)
                if not isinstance(item, dict) or not isinstance(item["id"], str) or item.get("role") not in ("state", "candidate") or not isinstance(item["text"], str):
                    raise ValueError("expected string id/text and role state|candidate")
            except (json.JSONDecodeError, KeyError, ValueError) as exc:
                raise ValueError(f"{path}:{line_no}: invalid corpus object: {exc}") from exc
            if (line_no - 1) % shards == shard:
                ids.append(item["id"]); texts.append(item["text"])
    return ids, texts


def _write_safetensors(path: Path, tensors: dict[str, np.ndarray]) -> None:
    header: dict[str, Any] = {}
    chunks, offset = [], 0
    for name, value in tensors.items():
        a = to_bf16(value)
        raw = (a.view(np.uint32) >> np.uint32(16)).astype("<u2").tobytes()
        header[name] = {"dtype": "BF16", "shape": list(a.shape), "data_offsets": [offset, offset + len(raw)]}
        chunks.append(raw); offset += len(raw)
    encoded = json.dumps(header, separators=(",", ":")).encode()
    path.write_bytes(struct.pack("<Q", len(encoded)) + encoded + b"".join(chunks))


def _dense_reference(ids: list[int], tensors: dict[str, np.ndarray], cfg: dict[str, Any]) -> np.ndarray:
    """All-weights-resident reference, deliberately independent of layer_forward."""
    h = to_bf16(tensors["model.embed_tokens.weight"][ids])
    eps = float(cfg["rms_norm_eps"])
    nq, nk, dim = (int(cfg[k]) for k in ("num_attention_heads", "num_key_value_heads", "head_dim"))
    for layer in range(int(cfg["num_hidden_layers"])):
        p = f"model.layers.{layer}."
        r = rms_norm(h, tensors[p + "input_layernorm.weight"], eps)
        q = linear(r, tensors[p + "self_attn.q_proj.weight"]).reshape(len(h), nq, dim)
        k = linear(r, tensors[p + "self_attn.k_proj.weight"]).reshape(len(h), nk, dim)
        v = linear(r, tensors[p + "self_attn.v_proj.weight"]).reshape(len(h), nk, dim)
        q = rms_norm(q, tensors[p + "self_attn.q_norm.weight"], eps)
        k = rms_norm(k, tensors[p + "self_attn.k_norm.weight"], eps)
        q, k = apply_rope(q, k, float(cfg["rope_theta"]))
        h = to_bf16(h + linear(attention(q, k, v), tensors[p + "self_attn.o_proj.weight"]))
        r = rms_norm(h, tensors[p + "post_attention_layernorm.weight"], eps)
        gate = linear(r, tensors[p + "mlp.gate_proj.weight"])
        gate = to_bf16(gate / (np.float32(1.0) + np.exp(-gate).astype(np.float32)))
        product = to_bf16(gate * linear(r, tensors[p + "mlp.up_proj.weight"]))
        h = to_bf16(h + linear(product, tensors[p + "mlp.down_proj.weight"]))
    return rms_norm(h, tensors["model.norm.weight"], eps)[-1].astype(np.float32)


def _selftest() -> None:
    rng = np.random.default_rng(7)
    cfg = {"hidden_size": 16, "num_hidden_layers": 2, "num_attention_heads": 4,
           "num_key_value_heads": 2, "head_dim": 4, "intermediate_size": 24,
           "rms_norm_eps": 1e-6, "rope_theta": 10000.0, "vocab_size": 32}
    tensors: dict[str, np.ndarray] = {"model.embed_tokens.weight": to_bf16(rng.normal(0, .1, (32, 16)))}
    for layer in range(2):
        p = f"model.layers.{layer}."
        shapes = [(16,), (16, 16), (8, 16), (8, 16), (4,), (4,), (16, 16),
                  (16,), (24, 16), (24, 16), (16, 24)]
        for key, shape in zip(LAYER_KEYS, shapes):
            value = np.ones(shape, np.float32) if key.endswith("layernorm.weight") or key.endswith("q_norm.weight") or key.endswith("k_norm.weight") else rng.normal(0, .08, shape)
            tensors[p + key] = to_bf16(value)
    tensors["model.norm.weight"] = np.ones(16, np.float32)
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        names = list(tensors)
        split = names.index("model.layers.0.self_attn.v_proj.weight")
        groups = [names[:split], names[split:]]
        weight_map = {name: f"model-0000{part + 1}-of-00002.safetensors" for part, group in enumerate(groups) for name in group}
        for part, group in enumerate(groups):
            _write_safetensors(root / f"model-0000{part + 1}-of-00002.safetensors", {n: tensors[n] for n in group})
        (root / "config.json").write_text(json.dumps(cfg))
        index = {"weight_map": weight_map}
        (root / "model.safetensors.index.json").write_text(json.dumps(index))
        tokens = [[1, 5, 3, 2], [7, 4]]
        streamed = encode_texts(tokens, cfg, index, str(root), root)
        # This path holds every weight at once and does not call layer_forward.
        dense = np.stack([_dense_reference(ids, tensors, cfg) for ids in tokens])
        assert streamed.shape == (2, 16) and np.isfinite(streamed).all()
        error = float(np.max(np.abs(streamed - dense)))
        assert error <= 1e-6, f"streamed/dense max abs {error}"
    print("selftest: ok")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", default="Qwen/Qwen3-8B")
    parser.add_argument("--corpus", type=Path)
    parser.add_argument("--out", type=Path)
    parser.add_argument("--shard", type=int, default=0)
    parser.add_argument("--shards", type=int, default=1)
    parser.add_argument("--max-text-tokens", type=int, default=2048)
    parser.add_argument("--cache-dir", type=Path)
    parser.add_argument("--hf-cross-check", action="store_true")
    parser.add_argument("--selftest", action="store_true")
    args = parser.parse_args(argv)
    if args.selftest:
        _selftest(); return 0
    if args.corpus is None or args.out is None:
        parser.error("--corpus and --out are required unless --selftest is used")
    if args.shards <= 0 or not 0 <= args.shard < args.shards:
        parser.error("require --shards > 0 and 0 <= --shard < --shards")
    if args.max_text_tokens <= 0:
        parser.error("--max-text-tokens must be positive")
    repo = Path(__file__).resolve().parents[2]
    cache = args.cache_dir or Path(os.environ.get("CLM_CACHE_DIR", repo / ".clm-cache"))
    cache.mkdir(parents=True, exist_ok=True)
    try:
        from transformers import AutoModel, AutoTokenizer
        cfg = json.loads(_metadata_file(args.model, "config.json", cache).read_text())
        index = {"weight_map": _weight_map(args.model, cache)}
        tokenizer = AutoTokenizer.from_pretrained(args.model, cache_dir=str(cache))
        ids, texts = _read_corpus(args.corpus, args.shard, args.shards)
        token_ids = [list(tokenizer(text, add_special_tokens=False)["input_ids"])[:args.max_text_tokens]
                     for text in texts]
        over = [text_id for text_id, row in zip(ids, token_ids) if len(row) > args.max_text_tokens]
        if over:
            raise ValueError(f"{over[0]} is over --max-text-tokens; the corpus must fit the cap unbroken")
        if any(not row for row in token_ids):
            raise ValueError("empty text/token sequence has no last-token hidden state")
        states = encode_texts(token_ids, cfg, index, args.model, cache)
        revision = _revision(args.model)
        meta = {"model": args.model, "revision": revision, "dtype": "bfloat16",
                "hidden_size": int(cfg["hidden_size"]), "num_layers": int(cfg["num_hidden_layers"]),
                "num_texts": len(ids), "max_text_tokens": args.max_text_tokens,
                "shard": args.shard, "shards": args.shards}
        args.out.parent.mkdir(parents=True, exist_ok=True)
        np.savez(args.out, ids=np.asarray(ids, dtype=str), states=states.astype(np.float32),
                 token_counts=np.asarray([len(row) for row in token_ids], dtype=np.int32),
                 meta=json.dumps(meta, sort_keys=True))
        if args.hf_cross_check:
            import torch
            try:
                model = AutoModel.from_pretrained(args.model, dtype=torch.bfloat16, cache_dir=str(cache)).eval()
            except TypeError:  # transformers < 4.56 spells the argument differently
                model = AutoModel.from_pretrained(args.model, torch_dtype=torch.bfloat16, cache_dir=str(cache)).eval()
            cosines = []
            with torch.no_grad():
                for row, expected in zip(token_ids, states):
                    actual = model(input_ids=torch.tensor([row], dtype=torch.long)).last_hidden_state[0, -1].float().numpy()
                    cosines.append(float(np.dot(actual, expected) / (np.linalg.norm(actual) * np.linalg.norm(expected))))
            values = np.asarray(cosines, dtype=np.float64)
            print(f"count={len(values)} mean={values.mean():.8f} p5={np.percentile(values, 5):.8f} min={values.min():.8f}")
            if values.size and values.min() < 0.9999:
                return 1
        else:
            print(f"count={len(ids)} hidden_size={cfg['hidden_size']}")
        return 0
    except (OSError, ValueError, KeyError, RuntimeError) as exc:
        print(f"ref_encoder.py: error: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())

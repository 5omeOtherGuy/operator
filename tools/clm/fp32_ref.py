#!/usr/bin/env python3
"""The Python reference for the CLM-8B fp32 head path and the seeded head generator.

Two interchangeable numeric backends implement the same primitives:

  * `PureOps` — plain Python floats rounded to binary32 after **every** operation (`struct`-based);
  * `NumpyOps` — numpy `float32` arrays, with `np.add.accumulate` for the sequential fp32 sums.

Both must produce bit-identical results: the Kotlin port (`dev.operator.core.clm.ClmMath`) rounds
after every operation, accumulates dot products in binary32 in index order and never contracts
`a * b + c` into an FMA — which is what `PureOps` does and what `NumpyOps` does by construction
(products are materialised and rounded before they are added). `make_goldens.py --check` runs both
backends and diffs the fixtures, and the JVM golden tests compare Kotlin against the committed
fixtures within 1e-4 (FOUNDATION §5.4; 03§F1.4-F1.6, F6.2-F6.4).

The forward pass, in the reference code's order (03§F1.5):

    x = gelu(inp(x)); x = gelu(norm(hidden(x))); z = l2(out(x)); score = min(exp(logit_scale), 100) * cos

with exact-erf GELU, LayerNorm eps 1e-5, `inp` 4096->1536, `hidden.0` 1536->1536, `norm` 1536,
`out` 1536->512, and the input L2-normalised before the heads (03§F1.8).
"""

from __future__ import annotations

import json
import math
import struct
from dataclasses import dataclass

INPUT_DIM = 4096
HIDDEN_DIM = 1536
OUTPUT_DIM = 512


def f32(v: float) -> float:
    """The nearest binary32 to `v`, as a Python float."""
    return struct.unpack("<f", struct.pack("<f", v))[0]


LN_EPS = f32(1e-5)
# The value stored in the published checkpoint's `logit_scale` tensor, read from it bit for bit by
# `convert_heads.py` (03§F1.6 writes the decimal 4.61325; exp(4.6132488...) > 100, so the clamped
# scale is exactly 100.0).
LOGIT_SCALE = 4.613248825073242
SCALE_CLAMP = 100.0
RECIPE_VERSION = "clm-v0.1-heads-1"

# The seeded generator's constants (mirrored by the JVM tests' ClmWeights helper).
SEED = 0
GAMMA = 0x9E3779B97F4A7C15
MIX1 = 0xBF58476D1CE4E5B9
MIX2 = 0x94D049BB133111EB
MASK64 = (1 << 64) - 1
UNIF_SCALE = 8388608.0  # 2**23: (z >> 40) / 2**23 - 1 is in [-1, 1) and exact in binary32

# Tensor order of the stream: the checkpoint's own order (03§F1.4), state head first.
TENSOR_SPEC: list[tuple[str, tuple[int, ...], str]] = [
    ("inp.weight", (HIDDEN_DIM, INPUT_DIM), "w"),
    ("inp.bias", (HIDDEN_DIM,), "b"),
    ("hidden.0.weight", (HIDDEN_DIM, HIDDEN_DIM), "w"),
    ("hidden.0.bias", (HIDDEN_DIM,), "b"),
    ("norms.0.weight", (HIDDEN_DIM,), "g"),
    ("norms.0.bias", (HIDDEN_DIM,), "b"),
    ("out.weight", (OUTPUT_DIM, HIDDEN_DIM), "w"),
    ("out.bias", (OUTPUT_DIM,), "b"),
]
HEAD_ORDER = ("state_head", "action_head")
WEIGHT_SCALE = 1.0
BIAS_SCALE = 0.05
GAMMA_SCALE = 0.1
BIAS_OFFSET = 0.0


def erf(x: float) -> float:
    """Exact enough `erf` in double precision; the same algorithm as `ClmMath.erf`.

    Taylor series for |x| < 3, the continued fraction of the complementary error function for
    3 <= |x| < 6, saturation beyond 6 (1 - erf(6) < 2.3e-17).
    """
    ax = abs(x)
    if ax >= 6.0:
        return -1.0 if x < 0 else 1.0
    if ax < 3.0:
        x2 = x * x
        term = x
        total = 0.0
        for n in range(48):
            total += term / (2.0 * n + 1.0)
            term *= -x2 / (n + 1.0)
        return 1.1283791670955126 * total
    fraction = 0.0
    for n in range(60, 0, -1):
        fraction = (n / 2.0) / (ax + fraction)
    erfc = math.exp(-ax * ax) / (1.772453850905516 * (ax + fraction))
    e = 1.0 - erfc
    return -e if x < 0 else e


class PureOps:
    """binary32 arithmetic on Python floats, rounded after every operation."""

    name = "pure"

    @staticmethod
    def r(v: float) -> float:
        return f32(v)

    def stream(self, seed: int) -> "PureStream":
        return PureStream(seed)

    def from_values(self, values) -> list[float]:
        """Weights read from a file: the values are already binary32."""
        return [float(v) for v in values]

    def uniform(self, stream: "PureStream", n: int) -> list[float]:
        return stream.uniform(n)

    def scale(self, v: list[float], s: float) -> list[float]:
        s32 = self.r(s)
        return [self.r(x * s32) for x in v]

    def add_scalar(self, v: list[float], c: float) -> list[float]:
        c32 = self.r(c)
        return [self.r(x + c32) for x in v]

    def copy(self, v: list[float]) -> list[float]:
        return list(v)

    def matvec_add(self, w, rows: int, cols: int, x, b) -> list[float]:
        out = []
        for row in range(rows):
            acc = 0.0
            off = row * cols
            for k in range(cols):
                acc = self.r(acc + self.r(w[off + k] * x[k]))
            out.append(self.r(acc + b[row]))
        return out

    def gelu(self, v: list[float]) -> list[float]:
        inv = self.r(1.0 / math.sqrt(2.0))
        out = []
        for x in v:
            t = self.r(x * inv)
            e = self.r(erf(t))
            out.append(self.r(self.r(0.5 * x) * self.r(1.0 + e)))
        return out

    def layernorm(self, x: list[float], gamma, beta, eps: float) -> list[float]:
        n = len(x)
        inv_n = self.r(float(n))
        mean = 0.0
        for value in x:
            mean = self.r(mean + value)
        mean = self.r(mean / inv_n)
        variance = 0.0
        d = []
        for value in x:
            delta = self.r(value - mean)
            d.append(delta)
            variance = self.r(variance + self.r(delta * delta))
        variance = self.r(variance / inv_n)
        inv_std = self.r(1.0 / math.sqrt(self.r(variance + self.r(eps))))
        return [self.r(self.r(self.r(di * inv_std) * g) + be) for di, g, be in zip(d, gamma, beta)]

    def l2norm(self, v: list[float]) -> list[float]:
        total = 0.0
        for value in v:
            total = self.r(total + self.r(value * value))
        norm = self.r(math.sqrt(total))
        return [self.r(value / norm) for value in v]

    def dot(self, a, b) -> float:
        total = 0.0
        for x, y in zip(a, b):
            total = self.r(total + self.r(x * y))
        return total

    def to_bytes(self, v) -> bytes:
        return struct.pack(f"<{len(v)}f", *v)

    def to_list(self, v) -> list[float]:
        return [float(x) for x in v]


class NumpyStream:
    """splitmix64, the same sequence as [PureStream], drawn with uint64 array arithmetic."""

    def __init__(self, seed: int = SEED, np=None):
        self.np = np if np is not None else _numpy()
        self.state = self.np.uint64(seed)

    def uniform(self, n: int) -> "object":
        np = self.np
        if n == 0:
            return np.zeros(0, dtype=np.float32)
        idx = np.arange(1, n + 1, dtype=np.uint64)
        state = self.state + idx * np.uint64(GAMMA)
        z = state
        z = (z ^ (z >> np.uint64(30))) * np.uint64(MIX1)
        z = (z ^ (z >> np.uint64(27))) * np.uint64(MIX2)
        z = z ^ (z >> np.uint64(31))
        m = (z >> np.uint64(40)) & np.uint64(0xFFFFFF)
        self.state = state[-1]
        return (m.astype(np.float64) / UNIF_SCALE - 1.0).astype(np.float32)


class PureStream:
    """splitmix64 over Python ints: `uniform(n)` returns n values in [-1, 1)."""

    def __init__(self, seed: int = SEED):
        self.state = seed & MASK64

    def _next_u64(self) -> int:
        self.state = (self.state + GAMMA) & MASK64
        z = self.state
        z = ((z ^ (z >> 30)) * MIX1) & MASK64
        z = ((z ^ (z >> 27)) * MIX2) & MASK64
        return (z ^ (z >> 31)) & MASK64

    def uniform(self, n: int) -> list[float]:
        out = []
        for _ in range(n):
            m = (self._next_u64() >> 40) & 0xFFFFFF
            out.append(m / UNIF_SCALE - 1.0)
        return out


class NumpyOps:
    """binary32 arithmetic on numpy arrays, with sequential fp32 sums."""

    name = "numpy"

    def __init__(self, np=None):
        self.np = np if np is not None else _numpy()

    def r(self, v: float) -> float:
        return float(self.np.float32(v))

    def stream(self, seed: int) -> "NumpyStream":
        return NumpyStream(seed, self.np)

    def from_values(self, values):
        """Weights read from a file: the values are already binary32."""
        return self.np.array(values, dtype=self.np.float32)

    def uniform(self, stream: NumpyStream, n: int):
        return stream.uniform(n)

    def scale(self, v, s: float):
        np = self.np
        return (v * np.float32(s)).astype(np.float32)

    def add_scalar(self, v, c: float):
        np = self.np
        return (v + np.float32(c)).astype(np.float32)

    def copy(self, v):
        return v.copy()

    def matvec_add(self, w, rows: int, cols: int, x, b):
        np = self.np
        products = (w.reshape(rows, cols) * x.reshape(1, cols)).astype(np.float32)
        acc = np.add.accumulate(products, axis=1, dtype=np.float32)[:, cols - 1]
        return (acc + b).astype(np.float32)

    def gelu(self, v):
        np = self.np
        inv = np.float32(1.0 / math.sqrt(2.0))
        t = (v * inv).astype(np.float32)
        e = np.array([np.float32(erf(float(value))) for value in t], dtype=np.float32)
        return ((v * np.float32(0.5)).astype(np.float32) * (np.float32(1.0) + e)).astype(np.float32)

    def layernorm(self, x, gamma, beta, eps: float):
        np = self.np
        n = x.shape[0]
        inv_n = np.float32(float(n))
        mean = np.add.accumulate(x, dtype=np.float32)[n - 1] / inv_n
        d = (x - mean).astype(np.float32)
        variance = np.add.accumulate((d * d).astype(np.float32), dtype=np.float32)[n - 1] / inv_n
        inv_std = np.float32(1.0 / math.sqrt(float(np.float32(variance + np.float32(eps)))))
        return (((d * inv_std).astype(np.float32) * gamma).astype(np.float32) + beta).astype(np.float32)

    def l2norm(self, v):
        np = self.np
        n = v.shape[0]
        total = np.add.accumulate((v * v).astype(np.float32), dtype=np.float32)[n - 1]
        norm = np.float32(math.sqrt(float(total)))
        return (v / norm).astype(np.float32)

    def dot(self, a, b) -> float:
        np = self.np
        n = a.shape[0]
        products = (a * b).astype(np.float32)
        return float(np.add.accumulate(products, dtype=np.float32)[n - 1])

    def to_bytes(self, v) -> bytes:
        return self.np.ascontiguousarray(v, dtype="<f4").tobytes()

    def to_list(self, v) -> list[float]:
        return [float(x) for x in v.tolist()]


def _numpy():
    import numpy  # noqa: PLC0415 - the numpy backend is optional by design

    return numpy


def make_ops(backend: str):
    """`backend` is "pure", "numpy" or "auto" (numpy when importable)."""
    if backend == "pure":
        return PureOps(), PureStream()
    if backend == "numpy":
        return NumpyOps(), NumpyStream()
    if backend != "auto":
        raise ValueError(f"unknown backend {backend!r}")
    try:
        ops = NumpyOps()
    except ImportError:
        return PureOps(), PureStream()
    return ops, NumpyStream()


@dataclass
class HeadWeights:
    """The eight tensors of one head, in a backend's own arrays."""

    inp_w: object
    inp_b: object
    hidden_w: object
    hidden_b: object
    norm_w: object
    norm_b: object
    out_w: object
    out_b: object


def build_weights(ops, stream) -> dict[str, HeadWeights]:
    """The seeded weights: `uniform()` draws in [TENSOR_SPEC] order, scaled per tensor kind."""
    heads: dict[str, HeadWeights] = {}
    for head in HEAD_ORDER:
        tensors = {}
        for name, shape, kind in TENSOR_SPEC:
            n = 1
            for dim in shape:
                n *= dim
            u = ops.uniform(stream, n)
            if kind == "w":
                value = ops.scale(u, WEIGHT_SCALE)
            elif kind == "b":
                value = ops.scale(u, BIAS_SCALE)
            elif kind == "g":
                value = ops.add_scalar(ops.scale(u, GAMMA_SCALE), 1.0)
            else:  # pragma: no cover - the spec above is closed
                raise AssertionError(kind)
            tensors[name] = value
        heads[head] = HeadWeights(
            inp_w=tensors["inp.weight"],
            inp_b=tensors["inp.bias"],
            hidden_w=tensors["hidden.0.weight"],
            hidden_b=tensors["hidden.0.bias"],
            norm_w=tensors["norms.0.weight"],
            norm_b=tensors["norms.0.bias"],
            out_w=tensors["out.weight"],
            out_b=tensors["out.bias"],
        )
    return heads


def weights_sha256(ops, heads: dict[str, HeadWeights]) -> str:
    """sha256 over the 16 tensors in [TENSOR_SPEC] order, little-endian fp32 bytes."""
    import hashlib

    digest = hashlib.sha256()
    for head in HEAD_ORDER:
        h = heads[head]
        for name, _shape, _kind in TENSOR_SPEC:
            field = {
                "inp.weight": h.inp_w,
                "inp.bias": h.inp_b,
                "hidden.0.weight": h.hidden_w,
                "hidden.0.bias": h.hidden_b,
                "norms.0.weight": h.norm_w,
                "norms.0.bias": h.norm_b,
                "out.weight": h.out_w,
                "out.bias": h.out_b,
            }[name]
            digest.update(ops.to_bytes(field))
    return digest.hexdigest()


def draw_raw_states(ops, stream, n: int, dims: int = INPUT_DIM, scale: float = 60.0) -> list[object]:
    """`n` raw (unnormalised) encoder states of `dims` values drawn from the stream."""
    return [ops.scale(ops.uniform(stream, dims), scale) for _ in range(n)]


def head_project(ops, head: HeadWeights, unit_input):
    """One head's forward pass on an already L2-normalised input (03§F1.5)."""
    a = ops.gelu(ops.matvec_add(head.inp_w, HIDDEN_DIM, INPUT_DIM, unit_input, head.inp_b))
    b = ops.layernorm(
        ops.matvec_add(head.hidden_w, HIDDEN_DIM, HIDDEN_DIM, a, head.hidden_b),
        head.norm_w,
        head.norm_b,
        LN_EPS,
    )
    b = ops.gelu(b)
    return ops.l2norm(ops.matvec_add(head.out_w, OUTPUT_DIM, HIDDEN_DIM, b, head.out_b))


def project_state(ops, heads: dict[str, HeadWeights], raw_state):
    """The state head's projection of a raw encoder state: L2-normalise, then the head (03§F1.8)."""
    return head_project(ops, heads["state_head"], ops.l2norm(ops.copy(raw_state)))


def project_action(ops, heads: dict[str, HeadWeights], raw_candidate):
    """The action head's projection of a raw candidate state."""
    return head_project(ops, heads["action_head"], ops.l2norm(ops.copy(raw_candidate)))


def score_scale(logit_scale: float = LOGIT_SCALE, clamp: float = SCALE_CLAMP) -> float:
    """`min(exp(logit_scale), clamp)`; 4.61325 gives exactly 100.0 (03§F1.6)."""
    return min(math.exp(logit_scale), clamp)


def score(ops, state_vector, action_vector, scale: float = SCALE_CLAMP) -> float:
    """`scale * cos(state, action)` for two L2-normalised projections."""
    return ops.r(ops.r(scale) * ops.dot(state_vector, action_vector))


# --- safetensors reading (the converted heads file); numpy only -------------------------------


def read_safetensors(path: str):
    """Returns `(metadata: dict[str,str], tensors: dict[str, np.ndarray])` for an fp32 file."""
    np = _numpy()
    with open(path, "rb") as handle:
        raw = handle.read()
    if len(raw) < 8:
        raise ValueError(f"{path}: too short for a safetensors file")
    (header_len,) = struct.unpack("<Q", raw[:8])
    header = json.loads(raw[8:8 + header_len].decode("utf-8"))
    data = raw[8 + header_len:]
    metadata = {str(k): str(v) for k, v in header.get("__metadata__", {}).items()}
    tensors = {}
    for name, entry in header.items():
        if name == "__metadata__":
            continue
        if entry["dtype"] != "F32":
            raise ValueError(f"{path}: tensor {name} is {entry['dtype']}, the fp32 path reads F32 only")
        begin, end = entry["data_offsets"]
        shape = tuple(entry["shape"])
        values = np.frombuffer(data[begin:end], dtype="<f4").reshape(shape).astype(np.float32)
        tensors[name] = values
    return metadata, tensors


def load_heads(path: str) -> tuple[dict[str, str], dict[str, HeadWeights]]:
    """Reads a converted heads file into [HeadWeights] per head, checking the recipe's shapes."""
    metadata, tensors = read_safetensors(path)
    heads = {}
    for head in HEAD_ORDER:
        expected = {
            "inp.weight": (HIDDEN_DIM, INPUT_DIM),
            "inp.bias": (HIDDEN_DIM,),
            "hidden.0.weight": (HIDDEN_DIM, HIDDEN_DIM),
            "hidden.0.bias": (HIDDEN_DIM,),
            "norms.0.weight": (HIDDEN_DIM,),
            "norms.0.bias": (HIDDEN_DIM,),
            "out.weight": (OUTPUT_DIM, HIDDEN_DIM),
            "out.bias": (OUTPUT_DIM,),
        }
        fields = {}
        for name, shape in expected.items():
            key = f"{head}.{name}"
            if key not in tensors:
                raise ValueError(f"{path}: tensor {key} is missing")
            if tensors[key].shape != shape:
                raise ValueError(f"{path}: tensor {key} has shape {tensors[key].shape}, expected {shape}")
            fields[name] = tensors[key].reshape(-1).astype(np.float32)
        heads[head] = HeadWeights(
            inp_w=fields["inp.weight"],
            inp_b=fields["inp.bias"],
            hidden_w=fields["hidden.0.weight"],
            hidden_b=fields["hidden.0.bias"],
            norm_w=fields["norms.0.weight"],
            norm_b=fields["norms.0.bias"],
            out_w=fields["out.weight"],
            out_b=fields["out.bias"],
        )
    return metadata, heads

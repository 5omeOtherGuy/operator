#!/usr/bin/env python3
"""Converts the published CLM-v0.1-8B head checkpoint to safetensors, without torch.

`CLM_v0.1-8B.pt` (Contrastive-LM/CLM-v0.1-8B) is a PyTorch **zip** archive, not safetensors: a
pickle (`qwen3_8b_posttrained_head/data.pkl`) that names the tensors, plus one raw file per storage
(`qwen3_8b_posttrained_head/data/<n>`). This script

  1. verifies the source sha256 against the pin before opening anything (FOUNDATION §4.8; the pin is
     the LFS sha256 of 03§F1.3, size 75,557,149 bytes),
  2. unpickles the checkpoint with a **restricted** unpickler that can only rebuild tensors —
     `collections.OrderedDict` and `torch._utils._rebuild_tensor_v2` with a `torch.FloatStorage` handle,
     nothing else, no `eval`, no module import, no torch installation,
  3. writes `clm-heads-v0.1.safetensors`: the 16 fp32 tensors under their checkpoint names
     (`state_head.inp.weight`, …) plus the scalar `logit_scale`, with the recipe metadata of
     03§F6.1 (`logit_scale`, `scale_clamp`, `width`, `depth`, `activation`, `ln_eps`, `source_sha256`,
     `recipe_version`, `source`),

and it never commits the weights: the output stays a CI artifact. The Kotlin loader
(`dev.operator.core.clm.ClmHeads.open`) reads exactly this file.

Usage:
    python3 tools/clm/convert_heads.py --source CLM_v0.1-8B.pt --out clm-heads-v0.1.safetensors
    python3 tools/clm/convert_heads.py --source CLM_v0.1-8B.pt --out heads.safetensors --inspect
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import math
import pickle
import struct
import sys
import zipfile
from pathlib import Path

SOURCE_SHA256 = "b2b4a8c9c2d39263eff78a351eb909a342ce9b3bf21a3f07c1d1bf15f1c4eda5"
SOURCE_SIZE = 75557149
SOURCE_REPO = "Contrastive-LM/CLM-v0.1-8B"
SOURCE_FILE = "CLM_v0.1-8B.pt"
RECIPE_VERSION = "clm-v0.1-heads-1"

# The recipe's own numbers (03§F1.4-F1.6): the widths, the depth, the LayerNorm eps and the clamp.
HEADS = ("state_head", "action_head")
TENSOR_SUFFIXES = (
    "inp.weight",
    "inp.bias",
    "hidden.0.weight",
    "hidden.0.bias",
    "norms.0.weight",
    "norms.0.bias",
    "out.weight",
    "out.bias",
)
EXPECTED_SHAPES = {
    "inp.weight": (1536, 4096),
    "inp.bias": (1536,),
    "hidden.0.weight": (1536, 1536),
    "hidden.0.bias": (1536,),
    "norms.0.weight": (1536,),
    "norms.0.bias": (1536,),
    "out.weight": (512, 1536),
    "out.bias": (512,),
}
WIDTH = 1536
DEPTH = 3
LN_EPS = 1e-5
SCALE_CLAMP = 100.0


class CheckpointError(RuntimeError):
    """The file is not the pinned checkpoint."""


class UnsupportedPickle(CheckpointError):
    """The pickle asks for something the restricted unpickler refuses to do."""


class TorchTensor:
    """The little a torch tensor needs to be: storage, offset, shape, stride, dtype."""

    def __init__(self, storage: "TorchStorage", offset: int, shape: tuple[int, ...], stride: tuple[int, ...], dtype: str):
        self.storage = storage
        self.offset = offset
        self.shape = tuple(shape)
        self.stride = tuple(stride)
        self.dtype = dtype

    @property
    def numel(self) -> int:
        n = 1
        for dim in self.shape:
            n *= dim
        return n

    def __len__(self) -> int:
        return self.shape[0] if self.shape else 0

    def __repr__(self) -> str:
        return f"TorchTensor({self.dtype}, shape={self.shape}, stride={self.stride}, offset={self.offset})"


class TorchStorage:
    """A storage handle: the pickle only carries its file key, the bytes come from the zip."""

    def __init__(self, key: str, numel: int, dtype: str = "FloatStorage"):
        self.key = key
        self.numel = numel
        self.dtype = dtype
        self.data: bytes | None = None

    def load(self, archive: dict[str, bytes]) -> None:
        name = None
        for candidate in (f"data/{self.key}", f"qwen3_8b_posttrained_head/data/{self.key}"):
            if candidate in archive:
                name = candidate
                break
        if name is None:
            raise CheckpointError(f"storage {self.key} is not in the archive; it holds {sorted(archive)}")
        self.data = archive[name]

    @property
    def nbytes(self) -> int:
        return self.numel * 4

    def float32(self) -> bytes:
        if self.data is None:
            raise CheckpointError(f"storage {self.key} was never loaded")
        if len(self.data) != self.nbytes:
            raise CheckpointError(
                f"storage {self.key} holds {len(self.data)} bytes, the pickle declares {self.numel} floats"
            )
        return self.data


class StorageMarker:
    """What `torch.FloatStorage` becomes: a marker that only ever appears inside a persistent id."""


class StateDict(dict):
    """`collections.OrderedDict` from the checkpoint: a dict that can carry the `_metadata` attribute."""


def _ordered_dict(*args, **kwargs):
    return StateDict(*args, **kwargs)


def _rebuild_tensor_v2(storage, storage_offset, size, stride, requires_grad=False, backward_hooks=None, *extra):
    """The torch function the checkpoint calls, rebuilt without torch."""
    if not isinstance(storage, TorchStorage):
        raise UnsupportedPickle(f"_rebuild_tensor_v2 got a {type(storage).__name__}, expected a storage")
    return TorchTensor(storage, int(storage_offset), tuple(int(dim) for dim in size), tuple(int(s) for s in stride), "float32")


class RestrictedUnpickler(pickle.Unpickler):
    """Only the checkpoint's own vocabulary; anything else is a hard failure."""

    def find_class(self, module: str, name: str):
        if module == "collections" and name == "OrderedDict":
            return _ordered_dict
        if module == "torch._utils" and name == "_rebuild_tensor_v2":
            return _rebuild_tensor_v2
        if module in ("torch", "torch.storage") and name in (
            "FloatStorage",
            "UntypedStorage",
            "TypedStorage",
            "DoubleStorage",
        ):
            # Only ever used inside a persistent id, which `persistent_load` turns into a storage.
            return StorageMarker
        raise UnsupportedPickle(f"the checkpoint asks for {module}.{name}, which is not allowed")

    def persistent_load(self, pid):
        if not isinstance(pid, tuple) or len(pid) < 4 or pid[0] != "storage":
            raise UnsupportedPickle(f"unsupported persistent id {pid!r}")
        _tag, _storage_type, key, _location = pid[0], pid[1], pid[2], pid[3]
        numel = int(pid[4]) if len(pid) > 4 else 0
        return TorchStorage(str(key), numel)


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def read_checkpoint(path: Path, expected_sha256: str | None = SOURCE_SHA256) -> dict:
    """Verifies the sha256, unpickles the archive and loads every storage it references."""
    if expected_sha256:
        actual = sha256_of(path)
        if actual != expected_sha256:
            raise CheckpointError(f"{path} has sha256 {actual}, the pin is {expected_sha256}")
    size = path.stat().st_size
    if size != SOURCE_SIZE:
        raise CheckpointError(f"{path} is {size} bytes, the pinned file is {SOURCE_SIZE} bytes")

    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        pkl_name = next((n for n in names if n.endswith("data.pkl")), None)
        if pkl_name is None:
            raise CheckpointError(f"{path} has no data.pkl; it holds {names}")
        prefix = pkl_name[: -len("data.pkl")]
        byteorder = next((n for n in names if n.endswith("byteorder")), None)
        if byteorder and archive.read(byteorder).strip() != b"little":
            raise CheckpointError("only little-endian checkpoints are supported")
        archive_data = {n: archive.read(n) for n in names if not n.endswith("data.pkl")}
        obj = RestrictedUnpickler(io.BytesIO(archive.read(pkl_name))).load()

    if not isinstance(obj, dict):
        raise CheckpointError(f"the checkpoint holds a {type(obj).__name__}, expected a dict")
    for key, value in obj.items():
        if isinstance(value, TorchTensor) and value.storage.data is None:
            value.storage.load(archive_data)
        if isinstance(value, dict):
            for inner in value.values():
                if isinstance(inner, TorchTensor) and inner.storage.data is None:
                    inner.storage.load(archive_data)
    obj["_prefix"] = prefix
    return obj


def tensor_of(obj: dict, name: str) -> TorchTensor:
    value = obj.get(name)
    if not isinstance(value, TorchTensor):
        raise CheckpointError(f"{name} is {type(value).__name__}, expected a tensor")
    return value


def scalar_of(tensor: TorchTensor) -> float:
    if tensor.numel != 1:
        raise CheckpointError(f"expected a scalar tensor, got {tensor.shape}")
    raw = tensor.storage.float32()
    start = tensor.offset * 4
    (value,) = struct.unpack_from("<f", raw, start)
    return float(value)


def convert(source: Path, out: Path, expected_sha256: str | None = SOURCE_SHA256, inspect: bool = False) -> dict:
    obj = read_checkpoint(source, expected_sha256)

    tensors: list[tuple[str, tuple[int, ...], bytes]] = []
    for head in HEADS:
        state = obj.get(head)
        if not isinstance(state, dict):
            raise CheckpointError(f"{head} is missing from the checkpoint")
        for suffix in TENSOR_SUFFIXES:
            tensor = tensor_of(state, suffix)
            if tensor.dtype != "float32":
                raise CheckpointError(f"{head}.{suffix} is {tensor.dtype}, expected float32")
            if tensor.shape != EXPECTED_SHAPES[suffix]:
                raise CheckpointError(f"{head}.{suffix} has shape {tensor.shape}, expected {EXPECTED_SHAPES[suffix]}")
            if tensor.stride != _contiguous(tensor.shape):
                raise CheckpointError(f"{head}.{suffix} is not contiguous: stride {tensor.stride}")
            raw = tensor.storage.float32()
            start = tensor.offset * 4
            end = start + tensor.numel * 4
            if end > len(raw):
                raise CheckpointError(f"{head}.{suffix} runs past its storage")
            tensors.append((f"{head}.{suffix}", tensor.shape, raw[start:end]))

    logit_scale = scalar_of(tensor_of(obj, "logit_scale"))
    cfg = obj.get("cfg") if isinstance(obj.get("cfg"), dict) else {}
    width = obj.get("width", cfg.get("width", WIDTH))
    depth = obj.get("depth", cfg.get("depth", DEPTH))
    if width != WIDTH or depth != DEPTH:
        raise CheckpointError(f"the checkpoint declares width={width} depth={depth}, expected {WIDTH}/{DEPTH}")
    if logit_scale <= 0:
        raise CheckpointError(f"logit_scale is {logit_scale}")

    metadata = {
        "logit_scale": repr(logit_scale),
        "scale_clamp": repr(float(SCALE_CLAMP)),
        "width": str(WIDTH),
        "depth": str(DEPTH),
        "activation": "gelu_erf",
        "ln_eps": repr(float(LN_EPS)),
        "source": f"{SOURCE_REPO}/{SOURCE_FILE}",
        "source_sha256": expected_sha256 or sha256_of(source),
        "recipe_version": RECIPE_VERSION,
    }
    write_safetensors(out, tensors + [("logit_scale", (), struct.pack("<f", logit_scale))], metadata)

    report = {
        "source": str(source),
        "source_sha256": metadata["source_sha256"],
        "out": str(out),
        "out_bytes": out.stat().st_size,
        "out_sha256": sha256_of(out),
        "logit_scale": logit_scale,
        "scale": min(math.exp(logit_scale), SCALE_CLAMP),
        "tensors": [
            {"name": name, "shape": list(shape), "dtype": "F32", "bytes": len(payload)}
            for name, shape, payload in tensors
        ],
        "metadata": metadata,
    }
    if inspect:
        print(json.dumps(report, indent=2))
    return report


def _contiguous(shape: tuple[int, ...]) -> tuple[int, ...]:
    stride = []
    acc = 1
    for dim in reversed(shape):
        stride.append(acc)
        acc *= dim
    return tuple(reversed(stride))


def write_safetensors(out: Path, tensors: list[tuple[str, tuple[int, ...], bytes]], metadata: dict[str, str]) -> None:
    """Writes the canonical layout: u64 header length, header JSON, raw little-endian data."""
    header: dict[str, object] = {}
    offset = 0
    for name, shape, payload in tensors:
        header[name] = {"dtype": "F32", "shape": list(shape), "data_offsets": [offset, offset + len(payload)]}
        offset += len(payload)
    header["__metadata__"] = metadata
    header_json = json.dumps(header, separators=(",", ":"), sort_keys=False).encode("utf-8")
    out.parent.mkdir(parents=True, exist_ok=True)
    with open(out, "wb") as handle:
        handle.write(struct.pack("<Q", len(header_json)))
        handle.write(header_json)
        for _name, _shape, payload in tensors:
            handle.write(payload)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--source", required=True, help="the pinned CLM_v0.1-8B.pt")
    parser.add_argument("--out", required=True, help="where to write clm-heads-v0.1.safetensors")
    parser.add_argument("--sha256", default=SOURCE_SHA256, help="expected sha256 of the source ('-' to skip)")
    parser.add_argument("--inspect", action="store_true", help="print the tensor table and digests as JSON")
    args = parser.parse_args()

    expected = None if args.sha256 in ("", "-") else args.sha256
    try:
        report = convert(Path(args.source), Path(args.out), expected, args.inspect)
    except CheckpointError as error:
        print(f"convert_heads: {error}", file=sys.stderr)
        return 1
    print(
        f"convert_heads: {report['out']} ({report['out_bytes']} bytes, sha256 {report['out_sha256']}), "
        f"{len(report['tensors'])} tensors, logit_scale {report['logit_scale']:.6f} -> scale {report['scale']:.3f}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

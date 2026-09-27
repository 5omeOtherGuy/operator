"""op-replay-v0: the recorded replay format (FOUNDATION §12; ADR-0015; research/07 §F3, §R1).

One JSON object per line (JSONL).  The first line SHOULD be the ``meta`` record; every later line is
a ``step`` record.  The format carries *screen trees only* (research 07 §F3: PNGs are dropped), so a
replay set is small enough to keep on the laptop under ``~/operator-eval`` and never enter the repo
(OQ-2 default (b)).

Record kinds
------------
meta
    ``{"v": "op-replay-v0", "type": "meta", "recordedAtMs": int, "device": str, "app": str,
       "source": "op-test"|"androidcontrol", "note": str}``
step
    ``{"type": "step", "i": int, "taskId": str, "goal": str, "instruction": str,
       "snapshot": <snapshot>, "gold": <gold>, "prediction": <prediction>?}``

``snapshot``
    ``{"id": int, "capturedAtMs": int, "foregroundPackage": str, "keyboardUp": bool,
       "focusedIndex": int|null, "structuralHash": int, "fullHash": int,
       "screenSignature": {"packageName": str, "windowTitle": str|null, "structuralHash": int},
       "windows": [{"id": int, "type": str, "layer": int, "packageName": str, "title": str|null,
                    "active": bool, "rootNodeIndex": int|null}],
       "nodes": [<node>]}``
    ``<node>``
    ``{"index": int, "key": str, "windowId": int, "packageName": str, "role": str, "label": str,
       "className": str|null, "viewId": str|null, "uniqueId": str|null,
       "bounds": {"left": int, "top": int, "right": int, "bottom": int},
       "depth": int, "parentIndex": int|null, "children": [int], "actions": [str], "state": [str],
       "row": int|null, "column": int|null, "windowTitle": str|null}``
    Roles are the OSF tokens (FOUNDATION §6.1 rule 4): ``btn txt edit switch chk radio tab list web
    link img menu seek``.  ``actions`` are the frozen ``NodeAction`` names (§7.2).

``gold`` (the reference action, AndroidControl action space per research 07 §F3)
    ``{"action": "click"|"long_press"|"scroll"|"open_app"|"input_text"|"navigate_home"|
                 "navigate_back"|"wait",
       "element": int|null,     # gold element index in the snapshot, when known
       "point": [x, y]|null,    # gold taps: the recorded (x, y)
       "text": str|null,        # input_text
       "direction": "down"|"up"|"left"|"right"|null,
       "app": str|null}         # open_app

``prediction`` (optional; written by the scorer's sibling producer, read by ``scorer.py``)
    ``{"raw": str, "parsed": {"name": str, "element": int|null, "text": str|null,
                              "direction": str|null, "package": str|null, "point": [x, y]|null}|null,
       "valid": bool?, "executable": bool?}``

The module is deliberately dependency-free (Python 3 stdlib only) and side-effect free; the readers
never touch a device (the brief: "never run anything against a phone").
"""

from __future__ import annotations

import json
from typing import Any, Dict, Iterable, Iterator, List, Optional, Tuple

VERSION = "op-replay-v0"

META = "meta"
STEP = "step"

ROLES = ("btn", "txt", "edit", "switch", "chk", "radio", "tab", "list", "web", "link", "img", "menu", "seek")

NODE_ACTIONS = (
    "CLICK", "LONG_CLICK", "SET_TEXT", "SCROLL_FORWARD", "SCROLL_BACKWARD",
    "FOCUS", "EXPAND", "COLLAPSE", "CHECK", "UNCHECK", "SELECT", "DISMISS",
)

#: The AndroidControl action space mapped onto our tool verbs (research 07 §F3).
GOLD_ACTIONS = (
    "click", "long_press", "scroll", "open_app", "input_text",
    "navigate_home", "navigate_back", "wait",
)

#: gold action -> the tool verb a correct prediction must name.
TOOL_FOR_GOLD = {
    "click": "click",
    "long_press": "long_click",
    "scroll": "scroll",
    "open_app": "launch_app",
    "input_text": "set_text",
    "navigate_home": "home",
    "navigate_back": "back",
    "wait": "wait",
}

#: The tap-like gold actions whose accuracy is decided by the AndroidControl bbox rule (§F3).
TAP_GOLD = ("click", "long_press")

#: The tool verbs the executor accepts for a tap-like prediction.
TAP_TOOLS = ("click", "long_click")


def bounds(left: int, top: int, right: int, bottom: int) -> Dict[str, int]:
    return {"left": int(left), "top": int(top), "right": int(right), "bottom": int(bottom)}


def node(
    index: int,
    key: str,
    window_id: int,
    package: str,
    role: str,
    label: str,
    bounds_in: Dict[str, int],
    *,
    class_name: Optional[str] = None,
    view_id: Optional[str] = None,
    unique_id: Optional[str] = None,
    depth: int = 0,
    parent_index: Optional[int] = None,
    children: Optional[Iterable[int]] = None,
    actions: Optional[Iterable[str]] = None,
    state: Optional[Iterable[str]] = None,
    row: Optional[int] = None,
    column: Optional[int] = None,
    window_title: Optional[str] = None,
) -> Dict[str, Any]:
    """Build one snapshot node.  ``role`` must be an OSF token (see :data:`ROLES`)."""
    if role not in ROLES:
        raise ValueError("unknown OSF role %r (expected one of %s)" % (role, ", ".join(ROLES)))
    unknown = [a for a in (actions or ()) if a not in NODE_ACTIONS]
    if unknown:
        raise ValueError("unknown NodeAction(s): %s" % ", ".join(unknown))
    return {
        "index": int(index),
        "key": str(key),
        "windowId": int(window_id),
        "packageName": package,
        "role": role,
        "label": label,
        "className": class_name,
        "viewId": view_id,
        "uniqueId": unique_id,
        "bounds": dict(bounds_in),
        "depth": int(depth),
        "parentIndex": parent_index,
        "children": [int(c) for c in (children or ())],
        "actions": [str(a) for a in (actions or ())],
        "state": [str(s) for s in (state or ())],
        "row": row,
        "column": column,
        "windowTitle": window_title,
    }


def window(
    window_id: int,
    package: str,
    *,
    window_type: str = "APPLICATION",
    layer: int = 0,
    title: Optional[str] = None,
    active: bool = True,
    root_node_index: Optional[int] = None,
) -> Dict[str, Any]:
    return {
        "id": int(window_id),
        "type": window_type,
        "layer": int(layer),
        "packageName": package,
        "title": title,
        "active": bool(active),
        "rootNodeIndex": root_node_index,
    }


def signature(package: str, window_title: Optional[str], structural_hash: int) -> Dict[str, Any]:
    return {
        "packageName": package,
        "windowTitle": window_title,
        "structuralHash": int(structural_hash),
    }


def snapshot(
    snapshot_id: int,
    nodes: List[Dict[str, Any]],
    *,
    captured_at_ms: int = 0,
    foreground_package: str = "",
    windows: Optional[List[Dict[str, Any]]] = None,
    structural_hash: int = 0,
    full_hash: int = 0,
    keyboard_up: bool = False,
    focused_index: Optional[int] = None,
    screen_signature: Optional[Dict[str, Any]] = None,
) -> Dict[str, Any]:
    """Assemble a snapshot record; indices in ``nodes`` must equal their list position."""
    for position, item in enumerate(nodes):
        if item.get("index") != position:
            raise ValueError("node index %r is not its list position %d" % (item.get("index"), position))
    return {
        "id": int(snapshot_id),
        "capturedAtMs": int(captured_at_ms),
        "foregroundPackage": foreground_package,
        "windows": list(windows or []),
        "nodes": list(nodes),
        "structuralHash": int(structural_hash),
        "fullHash": int(full_hash),
        "keyboardUp": bool(keyboard_up),
        "focusedIndex": focused_index,
        "screenSignature": screen_signature or signature(foreground_package, None, structural_hash),
    }


def meta(source: str = "op-test", *, recorded_at_ms: int = 0, device: str = "", app: str = "", note: str = "") -> Dict[str, Any]:
    return {
        "v": VERSION,
        "type": META,
        "recordedAtMs": int(recorded_at_ms),
        "device": device,
        "app": app,
        "source": source,
        "note": note,
    }


def step(
    index: int,
    task_id: str,
    goal: str,
    snap: Dict[str, Any],
    gold: Dict[str, Any],
    *,
    instruction: str = "",
    prediction: Optional[Dict[str, Any]] = None,
) -> Dict[str, Any]:
    """Build one ``step`` record.  ``gold`` uses the AndroidControl action space (§F3)."""
    action = gold.get("action")
    if action not in GOLD_ACTIONS:
        raise ValueError("unknown gold action %r (expected one of %s)" % (action, ", ".join(GOLD_ACTIONS)))
    record: Dict[str, Any] = {
        "type": STEP,
        "i": int(index),
        "taskId": task_id,
        "goal": goal,
        "instruction": instruction,
        "snapshot": snap,
        "gold": {
            "action": action,
            "element": gold.get("element"),
            "point": list(gold["point"]) if gold.get("point") is not None else None,
            "text": gold.get("text"),
            "direction": gold.get("direction"),
            "app": gold.get("app"),
        },
    }
    if prediction is not None:
        record["prediction"] = prediction
    return record


def write_jsonl(path: str, records: Iterable[Dict[str, Any]]) -> int:
    """Write ``records`` as JSONL.  Returns the number of lines written."""
    count = 0
    with open(path, "w", encoding="utf-8") as handle:
        for record in records:
            handle.write(json.dumps(record, ensure_ascii=False, sort_keys=True))
            handle.write("\n")
            count += 1
    return count


def iter_records(path: str, kinds: Optional[Iterable[str]] = None) -> Iterator[Dict[str, Any]]:
    """Yield the JSONL records of ``path``; blank lines are skipped, malformed lines raise."""
    wanted = set(kinds) if kinds else None
    with open(path, "r", encoding="utf-8") as handle:
        for number, line in enumerate(handle, 1):
            line = line.strip()
            if not line:
                continue
            try:
                record = json.loads(line)
            except json.JSONDecodeError as exc:
                raise ValueError("%s:%d: not JSON: %s" % (path, number, exc)) from exc
            if not isinstance(record, dict):
                raise ValueError("%s:%d: record is not an object" % (path, number))
            if wanted is not None and record.get("type") not in wanted:
                continue
            yield record


def bbox_of(node_record: Dict[str, Any]) -> Optional[Tuple[int, int, int, int]]:
    b = node_record.get("bounds")
    if not isinstance(b, dict):
        return None
    try:
        return (int(b["left"]), int(b["top"]), int(b["right"]), int(b["bottom"]))
    except (KeyError, TypeError, ValueError):
        return None


def point_in_bbox(point: Tuple[int, int], box: Tuple[int, int, int, int]) -> bool:
    x, y = int(point[0]), int(point[1])
    left, top, right, bottom = box
    return left <= x <= right and top <= y <= bottom


def element_bbox(snap: Dict[str, Any], element_index: Any) -> Optional[Tuple[int, int, int, int]]:
    if element_index is None:
        return None
    nodes = snap.get("nodes") or []
    try:
        index = int(element_index)
    except (TypeError, ValueError):
        return None
    if index < 0 or index >= len(nodes):
        return None
    return bbox_of(nodes[index])


def normalise_text(text: Optional[str]) -> str:
    """Whitespace/case-normalised comparison for ``input_text`` (research 07 §F3, our choice)."""
    return " ".join((text or "").split()).casefold()

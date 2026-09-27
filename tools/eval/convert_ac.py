#!/usr/bin/env python3
"""convert_ac.py — AndroidControl subset → op-replay-v0 (FOUNDATION §12; ADR-0015; research/07 §F3/R1).

``ac-sub-v0`` is "500 steps from AndroidControl task-unseen + app-unseen, trees only, converted once
in a manual CI job" (research 07 §R1).  The full dataset is 49.9 GB of TFRecord shards; extracting the
proto is the CI job's ``tf`` step, and *this* script is the deterministic part: it maps an
AndroidControl step (a11y forest + action JSON + goal) onto the ``op-replay-v0`` shape, dropping the
PNG.

Input is JSONL, one AndroidControl step per line, each with:

* ``goal`` and ``step_instruction``;
* an accessibility forest under ``accessibility_tree`` / ``a11y_tree`` / ``tree`` (a
  ``{"nodes": [...]}`` object, a ``{"trees": [...]}`` forest, or a bare node list);
* the reference action under ``action`` (an object or a JSON string), in the AndroidControl action
  space: ``click x,y``, ``long_press``, ``scroll dir``, ``open_app``, ``input_text``,
  ``navigate_home/back``, ``wait`` (research 07 §F3).

Node text wins in the §6.1 rule 5 order: ``text`` > ``content_description`` > ``hint_text``.  A tap's
gold element is the smallest clickable node containing the point (research 07 §F3).

Python 3 stdlib only; no phone, no network.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from typing import Any, Dict, Iterable, Iterator, List, Optional, Tuple

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import replay_v0  # noqa: E402

#: ac-sub-v0 size (research 07 §R1: 500 steps).
AC_SUB_V0_STEPS = 500

_TREE_KEYS = ("accessibility_tree", "a11y_tree", "tree", "ui_tree")
_EDITABLE_CLASSES = ("edittext", "autocompletetextview", "searchview")
_TEXT_CLASSES = ("textview", "checkedtextview")
_IMAGE_CLASSES = ("imageview", "imagebutton")
_WEB_CLASSES = ("webview",)
_LIST_CLASSES = ("recyclerview", "listview", "scrollview", "gridview", "viewpager")
_SEEK_CLASSES = ("seekbar", "ratingbar")
_SWITCH_CLASSES = ("switch", "togglebutton")
_CHECK_CLASSES = ("checkbox", "checkedtextview")
_RADIO_CLASSES = ("radiobutton",)


def _class_token(value: Any) -> str:
    return str(value or "").rsplit(".", 1)[-1].strip().lower()


def _bounds_of(node: Dict[str, Any]) -> Dict[str, int]:
    for key in ("bounds_in_screen", "bounds", "bounds_in_parent"):
        raw = node.get(key)
        if isinstance(raw, dict):
            return replay_v0.bounds(raw.get("left", 0), raw.get("top", 0),
                                    raw.get("right", 0), raw.get("bottom", 0))
        if isinstance(raw, (list, tuple)) and len(raw) == 4:
            return replay_v0.bounds(*raw)
    return replay_v0.bounds(0, 0, 0, 0)


def _label_of(node: Dict[str, Any]) -> str:
    # §6.1 rule 5 precedence: text > contentDescription > hint.
    for key in ("text", "content_description", "hint_text"):
        value = node.get(key)
        if value:
            return " ".join(str(value).split())
    return ""


def _role_of(node: Dict[str, Any]) -> str:
    klass = _class_token(node.get("class_name"))
    label = _label_of(node)
    if node.get("is_editable") or any(k in klass for k in _EDITABLE_CLASSES):
        return "edit"
    for token, role in ((_WEB_CLASSES, "web"), (_LIST_CLASSES, "list"), (_SEEK_CLASSES, "seek"),
                        (_SWITCH_CLASSES, "switch"), (_RADIO_CLASSES, "radio"),
                        (_CHECK_CLASSES, "chk"), (_IMAGE_CLASSES, "img"), (_TEXT_CLASSES, "txt")):
        if any(k in klass for k in token):
            return role
    if node.get("is_clickable") or node.get("is_long_clickable"):
        return "btn"
    if node.get("is_checkable"):
        return "chk"
    if label:
        return "txt"
    return "txt"


def _actions_of(node: Dict[str, Any]) -> List[str]:
    actions: List[str] = []
    if node.get("is_clickable"):
        actions.append("CLICK")
    if node.get("is_long_clickable"):
        actions.append("LONG_CLICK")
    if node.get("is_editable"):
        actions.append("SET_TEXT")
    if node.get("is_scrollable"):
        actions.append("SCROLL_FORWARD")
        actions.append("SCROLL_BACKWARD")
    if node.get("is_checkable"):
        actions.append("CHECK")
        actions.append("UNCHECK")
    if node.get("is_selected"):
        actions.append("SELECT")
    if node.get("is_focused"):
        actions.append("FOCUS")
    return actions


def _state_of(node: Dict[str, Any]) -> List[str]:
    state: List[str] = []
    if node.get("is_checked"):
        state.append("CHECKED")
    if node.get("is_selected"):
        state.append("SELECTED")
    if node.get("is_focused"):
        state.append("FOCUSED")
    if node.get("is_enabled") is False:
        state.append("DISABLED")
    if node.get("is_password"):
        state.append("PASSWORD")
    return state


def _nodes_of(record: Dict[str, Any]) -> List[Dict[str, Any]]:
    forest: Any = None
    for key in _TREE_KEYS:
        if record.get(key):
            forest = record[key]
            break
    if forest is None:
        return []
    if isinstance(forest, dict):
        if isinstance(forest.get("nodes"), list):
            return list(forest["nodes"])
        trees = forest.get("trees")
        if isinstance(trees, list):
            merged: List[Dict[str, Any]] = []
            for tree in trees:
                if isinstance(tree, dict) and isinstance(tree.get("nodes"), list):
                    merged += tree["nodes"]
            return merged
    if isinstance(forest, list):
        return list(forest)
    return []


def forest_to_snapshot(record: Dict[str, Any], *, snapshot_id: int = 0) -> Dict[str, Any]:
    """Map an AndroidControl a11y forest onto an op-replay-v0 snapshot."""
    raw_nodes = _nodes_of(record)
    by_node_id: Dict[Any, int] = {}
    for position, raw in enumerate(raw_nodes):
        identifier = raw.get("node_id", raw.get("unique_id"))
        if identifier is not None:
            by_node_id[identifier] = position

    package = ""
    for raw in raw_nodes:
        if raw.get("package_name"):
            package = str(raw["package_name"])
            break

    nodes: List[Dict[str, Any]] = []
    children_from_parent: Dict[int, List[int]] = {}
    for position, raw in enumerate(raw_nodes):
        parent = raw.get("parent_id")
        parent_index = by_node_id.get(parent) if parent is not None else None
        if parent_index is not None:
            children_from_parent.setdefault(parent_index, []).append(position)
    for position, raw in enumerate(raw_nodes):
        parent = raw.get("parent_id")
        parent_index = by_node_id.get(parent) if parent is not None else None
        children = []
        for child in raw.get("child_ids", []) or []:
            if child in by_node_id:
                children.append(by_node_id[child])
        if not children:
            # The TFRecord dump does not always carry child_ids; the parent links are enough.
            children = children_from_parent.get(position, [])
        nodes.append(replay_v0.node(
            position,
            str(raw.get("node_id", raw.get("unique_id", position))),
            int(raw.get("window_id", 0) or 0),
            str(raw.get("package_name", package) or package),
            _role_of(raw),
            _label_of(raw),
            _bounds_of(raw),
            class_name=raw.get("class_name"),
            view_id=raw.get("view_id", raw.get("resource_name")),
            unique_id=raw.get("unique_id"),
            depth=int(raw.get("depth", 0) or 0),
            parent_index=parent_index,
            children=children,
            actions=_actions_of(raw),
            state=_state_of(raw),
        ))
    windows = []
    window_ids = sorted({node["windowId"] for node in nodes})
    for window_id in window_ids:
        root = next((n["index"] for n in nodes if n["windowId"] == window_id), None)
        windows.append(replay_v0.window(window_id, package, root_node_index=root))
    return replay_v0.snapshot(snapshot_id, nodes, foreground_package=package, windows=windows)


def _action_of(record: Dict[str, Any]) -> Optional[Dict[str, Any]]:
    action = record.get("action")
    if isinstance(action, str):
        try:
            action = json.loads(action)
        except json.JSONDecodeError:
            return None
    if not isinstance(action, dict):
        return None
    return action


def gold_from_action(action: Dict[str, Any], snap: Dict[str, Any]) -> Optional[Dict[str, Any]]:
    """Map an AndroidControl action onto a replay gold, resolving the tap element."""
    kind = str(action.get("action_type") or action.get("type") or "").strip().lower()
    if kind in ("click", "long_press", "touch"):
        x, y = action.get("x"), action.get("y")
        if x is None or y is None:
            return None
        element = smallest_interactable_containing(snap, (int(x), int(y)))
        mapped = "click" if kind in ("click", "touch") else "long_press"
        return {"action": mapped, "element": element, "point": [int(x), int(y)]}
    if kind == "input_text":
        return {"action": "input_text", "text": action.get("text", "")}
    if kind == "scroll":
        return {"action": "scroll", "direction": str(action.get("direction", "")).lower()}
    if kind == "open_app":
        return {"action": "open_app", "app": action.get("app_name", action.get("app", ""))}
    if kind == "navigate_home":
        return {"action": "navigate_home"}
    if kind == "navigate_back":
        return {"action": "navigate_back"}
    if kind == "wait":
        return {"action": "wait"}
    return None


def smallest_interactable_containing(snap: Dict[str, Any], point: Tuple[int, int]) -> Optional[int]:
    """The smallest CLICK-able node whose bbox contains the point (research 07 §F3)."""
    best_index: Optional[int] = None
    best_area: Optional[int] = None
    for node in snap.get("nodes") or []:
        box = replay_v0.bbox_of(node)
        if box is None or not replay_v0.point_in_bbox(point, box):
            continue
        if "CLICK" not in (node.get("actions") or ()):
            continue
        area = max(0, box[2] - box[0]) * max(0, box[3] - box[1])
        if best_area is None or area <= best_area:
            best_area = area
            best_index = node.get("index")
    return best_index


def convert_record(record: Dict[str, Any], index: int) -> Optional[Dict[str, Any]]:
    """One AndroidControl step -> one op-replay-v0 step, or ``None`` when it cannot be converted."""
    action = _action_of(record)
    if action is None:
        return None
    snap = forest_to_snapshot(record, snapshot_id=index)
    if not snap["nodes"]:
        return None
    gold = gold_from_action(action, snap)
    if gold is None:
        return None
    task_id = str(record.get("episode_id", record.get("episode", record.get("task_id", ""))) or "")
    goal = str(record.get("goal", "") or "")
    instruction = str(record.get("step_instruction", record.get("instruction", "")) or "")
    return replay_v0.step(index, task_id, goal, snap, gold, instruction=instruction)


def convert_stream(records: Iterable[Dict[str, Any]], *, max_steps: int = AC_SUB_V0_STEPS,
                   source: str = "androidcontrol", device: str = "", app: str = "",
                   recorded_at_ms: int = 0) -> List[Dict[str, Any]]:
    """Convert a stream of AndroidControl steps, stopping after ``max_steps`` converted steps."""
    out: List[Dict[str, Any]] = [replay_v0.meta(source, recorded_at_ms=recorded_at_ms, device=device,
                                               app=app, note="AndroidControl subset")]
    converted = 0
    for record in records:
        if converted >= max_steps:
            break
        step = convert_record(record, converted)
        if step is None:
            continue
        out.append(step)
        converted += 1
    return out


def convert_file(path: str, out_path: str, *, max_steps: int = AC_SUB_V0_STEPS, **meta: Any) -> int:
    """Stream a JSONL AndroidControl file into an op-replay-v0 file; returns the step count."""

    def records() -> Iterator[Dict[str, Any]]:
        with open(path, "r", encoding="utf-8") as handle:
            for number, line in enumerate(handle, 1):
                line = line.strip()
                if not line:
                    continue
                try:
                    yield json.loads(line)
                except json.JSONDecodeError as exc:
                    raise ValueError("%s:%d: not JSON: %s" % (path, number, exc)) from exc

    out = convert_stream(records(), max_steps=max_steps, **meta)
    replay_v0.write_jsonl(out_path, out)
    return len(out) - 1


def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="AndroidControl subset -> op-replay-v0")
    parser.add_argument("--input", required=True, help="AndroidControl steps as JSONL")
    parser.add_argument("--out", required=True, help="the op-replay-v0 JSONL to write")
    parser.add_argument("--max-steps", type=int, default=AC_SUB_V0_STEPS, help="default 500 (ac-sub-v0)")
    parser.add_argument("--device", default="")
    parser.add_argument("--app", default="")
    args = parser.parse_args(argv)

    count = convert_file(args.input, args.out, max_steps=args.max_steps,
                         source="androidcontrol", device=args.device, app=args.app)
    print("%d steps -> %s" % (count, args.out))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

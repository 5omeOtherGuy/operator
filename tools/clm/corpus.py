#!/usr/bin/env python3
"""Builds the CLM fidelity corpus (FOUNDATION §5.4 / 03§F5.2) as deterministic JSONL.

One line per encoded text:

    {"id": "0007-state", "role": "state", "text": "<context>\\n\\n<instructions>"}
    {"id": "0007-cand0", "role": "candidate", "text": "<candidate text>"}

The texts are built by the same rules as the Kotlin port (`dev.operator.core.clm.ClmSchema`, mirrored
here by `schema_ref.py`): `state_text = context + "\\n\\n" + instructions`, the four question kinds' own
candidate renderings, plain text only. §F5.2 wants ~300 states × their questions from the public
`typed-decisions` set and the operator synthetic set; lane 07/S12 owns that set and this script is the
placeholder with the same shape — synthetic screens written here, deterministic from `--seed`, no
private data, no network.

Usage:
    python3 tools/clm/corpus.py --states 60 --out corpus.jsonl
"""

from __future__ import annotations

import argparse
import json
import random
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import schema_ref as schema  # noqa: E402

APPS = [
    ("com.android.settings", "Settings"),
    ("com.android.messaging", "Messages"),
    ("com.android.dialer", "Phone"),
    ("com.android.camera2", "Camera"),
    ("com.android.deskclock", "Clock"),
    ("com.android.documentsui", "Files"),
    ("com.android.calendar", "Calendar"),
    ("com.android.contacts", "Contacts"),
]

ROWS = [
    "Wi-Fi",
    "Bluetooth",
    "Battery",
    "Display",
    "Sound",
    "Storage",
    "Apps",
    "Notifications",
    "Privacy",
    "Location",
    "Accessibility",
    "About phone",
    "Airplane mode",
    "Hotspot",
    "Night light",
    "Adaptive brightness",
]

STATES = ["off", "on", "greyed out", "highlighted", "selected", "disabled"]


def screen(random_source: random.Random, index: int, rows: int) -> tuple[str, str]:
    """A prose rendering of one synthetic screen (context first, question last)."""
    package, label = random_source.choice(APPS)
    lines = [f"Screen: {label} ({package})."]
    if random_source.random() < 0.3:
        lines.append("A keyboard is up.")
    if random_source.random() < 0.4:
        lines.append(f'The search field shows "{random_source.choice(ROWS)}".')
    for _ in range(rows):
        row = random_source.choice(ROWS)
        state = random_source.choice(STATES)
        lines.append(f'- Row "{row}" is {state}.')
    if random_source.random() < 0.5:
        lines.append(f'The button "{random_source.choice(ROWS)}" is in the bottom bar.')
    lines.append(f"Screen id {index}.")
    return "\n".join(lines), label


def question(random_source: random.Random, label: str) -> dict:
    """One question of a rotating kind, with the instructions and the options it needs."""
    kind = random_source.choice(["yesno", "choice", "score", "rank"])
    row = random_source.choice(ROWS)
    if kind == "yesno":
        return {
            "kind": "yesno",
            "instructions": f'Is the row "{row}" on?',
        }
    if kind == "choice":
        picked = random_source.sample(ROWS, 3)
        return {
            "kind": "choice",
            "instructions": f"Which row opens {label}'s {picked[0]} screen?",
            "options": {chr(ord("a") + i): value for i, value in enumerate(picked)},
        }
    if kind == "score":
        return {
            "kind": "score",
            "instructions": f'Did the tap on "{row}" take effect?',
            "levels": ["No, the screen is unchanged", "Unclear", "Yes, the next screen is visible"],
        }
    return {
        "kind": "rank",
        "instructions": "Rank the next steps.",
        "candidates": [
            f'Tap "{row}"',
            f'Tap "{random_source.choice(ROWS)}"',
            "Scroll down",
        ],
    }


def build(states: int, seed: int) -> list[dict]:
    """One state text and its candidate texts per synthetic screen.

    Every text stays inside the 2048-token cap under any tokenizer: a byte-level BPE emits at most one
    token per byte, so the byte count bounds the token count. The gates therefore never have to
    truncate, and the reference and llama.cpp always see the identical token sequence.
    """
    random_source = random.Random(seed)
    lines: list[dict] = []
    for index in range(states):
        rows = random_source.randint(6, 18)
        context, label = screen(random_source, index, rows)
        ask = question(random_source, label)
        text = schema.state_text(context, ask["instructions"])
        tokens = schema.byte_tokenize(text)
        if len(tokens) > schema.MAX_TOKENS:
            raise SystemExit(f"state {index} is {len(tokens)} bytes, over the {schema.MAX_TOKENS}-token cap")
        lines.append({"id": f"{index:04d}-state", "role": "state", "text": text})
        for j, candidate in enumerate(
            schema.candidate_texts(
                ask["kind"],
                ask["instructions"],
                options=ask.get("options"),
                levels=ask.get("levels"),
                candidates=ask.get("candidates"),
            )
        ):
            lines.append({"id": f"{index:04d}-cand{j}", "role": "candidate", "text": candidate})
    return lines


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--states", type=int, default=60, help="how many synthetic screens")
    parser.add_argument("--seed", type=int, default=0)
    parser.add_argument("--out", required=True)
    args = parser.parse_args()

    lines = build(args.states, args.seed)
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    with open(out, "w", encoding="utf-8") as handle:
        for line in lines:
            handle.write(json.dumps(line, ensure_ascii=False) + "\n")

    states = sum(1 for line in lines if line["role"] == "state")
    candidates = len(lines) - states
    tokens = sum(len(schema.byte_tokenize(line["text"])) for line in lines)
    print(
        f"corpus: {out} — {states} states, {candidates} candidates, {len(lines)} texts, "
        f"{tokens} byte-tokens (seed {args.seed})"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env python3
"""The Python mirror of `dev.operator.core.clm.ClmSchema` (the port of CLM's `schema.py`).

This file exists for one reason: the JVM golden tests pin the Kotlin port against texts and token
sequences produced by an independent implementation, and the gate corpus is built with the same rules
(FOUNDATION §5.4; ADR-0007 decisions 2 and 7; docs/design/research/03-decide.md §F1.11-F1.12).

The recipe, from the reference code:
  state_text = context + "\\n\\n" + instructions        (context first, question last)
  noul:        "true: Yes. This is true: {q}" / "false: No. This is false: {q}"
  choice:      the option's description, or its key when the description is blank, no prefix
  score:       each rubric level's text
  rank:        the candidate string itself
  plain text only: no BOS, no EOS, no chat template, no special tokens;
  a state keeps its tail, a candidate keeps its head, at most 2048 tokens.

`byte_tokenize` is the fake tokenizer the fixtures are generated with: one token per UTF-8 byte. It
exercises the token-counting plumbing (a real tokenizer is injected on the phone: `LlmPort.tokenize`
with `addSpecial=false, parseSpecial=false`).
"""

from __future__ import annotations

MAX_TOKENS = 2048
RECIPE_VERSION = "clm-v0.1"


def state_text(context: str, instructions: str) -> str:
    """`context + "\\n\\n" + instructions`."""
    return context + "\n\n" + instructions


def yes_no_candidate(instructions: str, is_true: bool) -> str:
    """A `noul` candidate; `{q}` is the question text, i.e. the question's instructions."""
    if is_true:
        return f"true: Yes. This is true: {instructions}"
    return f"false: No. This is false: {instructions}"


def candidate_texts(
    kind: str,
    instructions: str,
    options: dict[str, str] | None = None,
    levels: list[str] | None = None,
    candidates: list[str] | None = None,
) -> list[str]:
    """The candidate texts of one question, in the order the port returns them."""
    if kind == "yesno":
        return [yes_no_candidate(instructions, True), yes_no_candidate(instructions, False)]
    if kind == "choice":
        return [text if text.strip() else key for key, text in (options or {}).items()]
    if kind == "score":
        return list(levels or [])
    if kind == "rank":
        return list(candidates or [])
    raise ValueError(f"unknown question kind {kind!r}")


def byte_tokenize(text: str) -> list[int]:
    """The fixture tokenizer: one token per UTF-8 byte."""
    return list(text.encode("utf-8"))


def keep_tail(ids: list[int], max_tokens: int = MAX_TOKENS) -> list[int]:
    """A state keeps its tail (03§F1.11)."""
    if max_tokens <= 0:
        raise ValueError("max_tokens must be positive")
    return list(ids[-max_tokens:])


def keep_head(ids: list[int], max_tokens: int = MAX_TOKENS) -> list[int]:
    """A candidate keeps its head (03§F1.11)."""
    if max_tokens <= 0:
        raise ValueError("max_tokens must be positive")
    return list(ids[:max_tokens])

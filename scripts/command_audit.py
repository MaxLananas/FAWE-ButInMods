#!/usr/bin/env python3
"""Audits how much each command handler actually does.

The inventory check proves every upstream name resolves; this audit is about the
other half of the port, that the handler behind a name is the ported behaviour and
not something that only answers. It reads the command sources, matches every
``handler = ctx -> { ... }`` body, and reports, per command:

* how many statements the handler has,
* how many of its declared arguments it reads (``ctx.arg``/``ctx.intArg``/...),
* whether it reaches an engine call (a session, a world, a pattern, a mask, a
  region, a clipboard, history, a generator, a file).

A handler that only speaks is not necessarily wrong - ``//size``, ``//count`` and
``//help`` exist to answer, so does ``//wand`` - which is why the audit prints a
table and lets the reader judge, and why it is a report rather than a gate. What
it catches is a name that was registered and left empty.

Usage:
    python3 scripts/command_audit.py [--dir core/src/main/java/com/maxlananas/fawebim/core/command]
                                     [--top 40]
"""

from __future__ import annotations

import argparse
import re
from pathlib import Path

COMMAND_DIR = "core/src/main/java/com/maxlananas/fawebim/core/command"

# Calls that mean the handler reached the engine rather than only the actor.
ENGINE_CALLS = re.compile(
    r"\b(?:session|world|edit|editSession|operations|Operations|Patterns|Masks|"
    r"BlockState|Schematic|Schematics|Clipboard|History|Snapshot|Snapshots|Regen|Anvil|"
    r"Region|Regions|HeightMaps|Generators|Brush|Tool|loadChunk|applyChunk)"
    r"\s*[.(]|(?:registry|dispatcher)\s*\.\s*\w+\s*\("
)
# A handler written as one expression delegates to a helper of the command class,
# which is engine work even though the body names nothing the pattern above knows.
SPEAKING_CALLS = re.compile(
    r"(?:ctx\s*\.\s*(?:error|actor)|actor\s*\.\s*(?:message|error)|Msg\s*\.\s*\w+)"
)
CALLS = re.compile(r"(?<![\w.])([A-Za-z_]\w*(?:\s*\.\s*[A-Za-z_]\w*)*)\s*\(")

ARG_READ = re.compile(
    r"\bctx\s*\.\s*(?:arg|intArg|doubleArg|hasFlag|flagValue|flagInt|joined|optional|mask|pattern|"
    r"region|bound|bounds|vector|direction)\s*\(")

HANDLER = re.compile(r"(\w+)\s*\.\s*handler\s*=\s*(?:ctx|\w+)\s*->\s*(?:\{|[^\n]+)")
REGISTRATION = re.compile(r"(?:registerUnlessPresent|\.register)\s*\(\s*((?:\"[^\"]*\"\s*,?\s*)+)")


def handler_bodies(text: str):
    """Yields (offset, body) for every handler lambda in the text."""
    for match in HANDLER.finditer(text):
        start = text.index("{", match.end() - 1) if "{" in match.group(0) else None
        if start is None:
            yield match.start(), match.group(0)
            continue
        depth = 0
        index = start
        while index < len(text):
            char = text[index]
            if char == "{":
                depth += 1
            elif char == "}":
                depth -= 1
                if depth == 0:
                    break
            index += 1
        yield match.start(), text[start:index + 1]


def command_names_before(text: str, offset: int) -> list[str]:
    """The names of the registration the handler at ``offset`` belongs to."""
    best = None
    for match in REGISTRATION.finditer(text, 0, offset):
        best = match
    if best is None:
        return ["?"]
    return re.findall(r'"([^"]+)"', best.group(1)) or ["?"]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dir", default=COMMAND_DIR)
    parser.add_argument("--top", type=int, default=0, help="print only the N shallowest handlers")
    args = parser.parse_args()

    rows: list[tuple[str, int, int, int]] = []
    for path in sorted(Path(args.dir).glob("*.java")):
        text = path.read_text()
        for offset, body in handler_bodies(text):
            name = command_names_before(text, offset)[0]
            statements = len([line for line in body.splitlines()
                              if line.strip() and not line.strip().startswith(("//", "*", "/*"))])
            engine = len(ENGINE_CALLS.findall(body))
            if "{" not in body and not SPEAKING_CALLS.search(body):
                engine = max(engine, 1)
            rows.append((name, max(0, statements - 1), len(ARG_READ.findall(body)), engine))

    rows.sort(key=lambda row: (row[3], row[1], row[0]))
    shown = rows[:args.top] if args.top else rows
    print(f"{'command':30s} {'lines':>6s} {'args':>5s} {'engine':>7s}")
    for name, statements, args_read, engine in shown:
        print(f"{name:30s} {statements:6d} {args_read:5d} {engine:7d}")

    silent = [row for row in rows if row[3] == 0]
    print()
    print(f"handlers read: {len(rows)}")
    print(f"handlers that only answer (no engine call): {len(silent)}")
    print(f"handlers that read no argument: "
          f"{len([row for row in rows if row[2] == 0])}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

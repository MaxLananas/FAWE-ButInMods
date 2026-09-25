#!/usr/bin/env python3
"""Checks that every WorldEdit/FAWE command line a player may type is answered.

A spelling is *typed* the way the player writes it, which is not always how it is
declared. WorldEdit declares a base name and gives it aliases, and an alias with
a leading slash is typed with one more: ``clearclipboard`` is typed
``/clearclipboard``, its alias ``/cc`` is typed ``//cc``. The mod registers a
literal per spelling, and a literal written with a leading slash answers both
forms, so an entry named ``/cc`` covers ``/cc`` and ``//cc``.

A sub-command of a container (``/brush sphere``, ``/tool tree``) is answered
either by an entry named after the full path or by the container's own argument
handling, which the container entry declares as ``[single|area|recursive]``.

Usage:
    python3 scripts/spelling_audit.py [--inventory docs/commands-inventory.json]
                                      [--spec docs/commands-spec.json]
"""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

INVENTORY = "docs/commands-inventory.json"
SPEC = "docs/commands-spec.json"

# The container literal a class of upstream commands is reached through.
CONTAINERS = {
    "BrushCommands.java": "brush",
    "PaintBrushCommands.java": "brush",
    "ApplyBrushCommands.java": "brush",
    "ToolCommands.java": "tool",
    "ToolUtilCommands.java": "tool",
    "SuperPickaxeCommands.java": "superpickaxe",
    "SchematicCommands.java": "schem",
    "HistorySubCommands.java": "history",
    "SnapshotCommands.java": "snapshot",
    "SnapshotUtilCommands.java": "snapshot",
    "AnvilCommands.java": "anvil",
    "CFICommands.java": "cfi",
    "WorldEditCommands.java": "we",
    "ListFilters.java": "list",
}

# Names WorldEdit declares for the CraftScript hooks, the super-pickaxe toggle
# and the filter lists: they are arguments or keybinds, not command lines.
NOT_A_COMMAND = re.compile(r"^[;,*/]$|^$|^-")


def typed(declared: str) -> set[str]:
    """The command lines an entry declared under this spelling answers."""
    path = declared[1:] if declared.startswith("//") else declared
    lines = {"/" + path}
    if path.startswith("/"):
        lines.add(path)
    return lines


def declared_names(entry: dict) -> list[str]:
    return [entry["name"], *(entry.get("aliases") or [])]


def arguments(entry: dict) -> set[str]:
    """Sub-command names an entry dispatches from its own first argument."""
    names: set[str] = set()
    for argument in entry.get("arguments") or []:
        for token in re.split(r"[|/]", argument):
            token = token.strip().strip("[]()<>").strip()
            if token and not token.startswith("-"):
                names.add(token)
    return names


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--inventory", default=INVENTORY)
    parser.add_argument("--spec", default=SPEC)
    args = parser.parse_args()

    inventory = json.loads(Path(args.inventory).read_text())
    spec = json.loads(Path(args.spec).read_text())["commands"]

    answered: set[str] = set()
    containers: dict[str, dict] = {}
    for entry in spec:
        for spelling in declared_names(entry):
            answered |= typed(spelling)
        for spelling in ("/" + entry["name"].split(" ")[0].lstrip("/"),):
            containers.setdefault(spelling, entry)

    expected: dict[str, tuple[str, str]] = {}
    for item in inventory:
        name = item["name"]
        if not name or NOT_A_COMMAND.match(name):
            continue
        source = item["source"]
        file = item["file"].split("/")[-1]
        container = CONTAINERS.get(file)
        for spelling in [name, *(item.get("aliases") or [])]:
            spelling = spelling.strip()
            if not spelling or NOT_A_COMMAND.match(spelling):
                continue
            if container is not None:
                line = "/" + container + " " + spelling.lstrip("/")
            else:
                line = "/" + spelling
            expected.setdefault(line, (source, file))

    missing = []
    for line, origin in sorted(expected.items()):
        if line in answered:
            continue
        head, _, tail = line.partition(" ")
        entry = containers.get(head)
        if tail and entry is not None and tail in arguments(entry):
            # The container parses the sub-name out of its own arguments.
            continue
        missing.append((line, origin))

    print(f"upstream command lines: {len(expected)}, not answered: {len(missing)}")
    for line, (source, file) in missing:
        print(f"  {line:34s} ({source} {file})")
    return 1 if missing else 0


if __name__ == "__main__":
    raise SystemExit(main())

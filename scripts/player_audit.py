#!/usr/bin/env python3
"""Checks the player-only flag of every command against the upstream declaration.

WorldEdit and FAWE declare a command with a ``Player`` parameter when it cannot
run without a body - the wand, the tools, the navigation, the brushes, ``//tree``
- and with an ``Actor`` when a console, a command block or a function can run it.
The dispatcher refuses the first kind for a source without a player, so the flag
decides what a console can reach: a command marked player-only that upstream
allows from an actor is a command line the mod rejects and the plugin accepts.

An entry is compared through the spelling it is registered under, against
WorldEdit's declaration of that spelling where there is one: FAWE's own rows
reach the player through ``InjectedValueAccess`` for several brushes rather than
through a parameter, and WorldEdit is what the behaviour follows.

Usage:
    python3 scripts/player_audit.py [--inventory docs/commands-inventory.json]
                                    [--spec docs/commands-spec.json]
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

# The command container a class of upstream commands belongs to.
CONTAINERS = {
    "BrushCommands": "brush",
    "PaintBrushCommands": "brush",
    "ApplyBrushCommands": "brush",
    "ToolCommands": "tool",
    "ToolUtilCommands": "tool",
    "SuperPickaxeCommands": "superpickaxe",
    "SchematicCommands": "schem",
    "HistorySubCommands": "history",
    "SnapshotCommands": "snapshot",
    "SnapshotUtilCommands": "snapshot",
    "AnvilCommands": "anvil",
    "CFICommands": "cfi",
    "WorldEditCommands": "we",
    "ListFilters": "list",
}


def key(spelling: str) -> str:
    """Normalizes a spelling: the name a source types, without its slashes."""
    return spelling.lstrip("/").lower()


def spelling(row: dict) -> str:
    """The spelling an inventory row declares, in the dispatcher's form."""
    name = (row.get("name") or "").strip()
    if not name:
        return ""
    plain = name.lstrip("/")
    container = CONTAINERS.get(Path(row.get("file", "")).stem, "")
    return f"{container} {plain}" if container else plain


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--inventory", default="docs/commands-inventory.json")
    parser.add_argument("--spec", default="docs/commands-spec.json")
    args = parser.parse_args()

    inventory = json.loads(Path(args.inventory).read_text())
    spec = json.loads(Path(args.spec).read_text())

    # What each spelling is declared as, WorldEdit's word first.
    worldedit: dict[str, bool] = {}
    fastasync: dict[str, bool] = {}
    for row in inventory:
        spelling_name = spelling(row)
        if not spelling_name:
            continue
        wants_player = any((parameter.get("type") or "").endswith(("Player", "InjectedValueAccess"))
                           for parameter in row.get("params", []))
        target = worldedit if row.get("source") == "WorldEdit" else fastasync
        target.setdefault(key(spelling_name), wants_player)

    problems = 0
    checked = 0
    for command in spec.get("commands", []):
        name = key(command["name"])
        if name in worldedit:
            upstream_player = worldedit[name]
        elif name in fastasync:
            upstream_player = fastasync[name]
        else:
            continue
        checked += 1
        if upstream_player != bool(command.get("requiresPlayer")):
            problems += 1
            print(f"{command['name']}: registered player-only="
                  f"{bool(command.get('requiresPlayer'))}, upstream={upstream_player}")
    print(f"commands compared: {checked}, player-only flags that disagree: {problems}")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())

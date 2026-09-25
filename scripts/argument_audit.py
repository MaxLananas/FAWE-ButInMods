#!/usr/bin/env python3
"""Compares the arguments of every command with its upstream declaration.

The inventory holds what WorldEdit and FAWE declare: which parameters are
``@Arg`` (positional), which have a default, and which are ``@Switch`` or
``@ArgFlag``. The command spec holds what this build registered, where an
optional argument is written between brackets. A required argument the build
does not take means a command line upstream runs and this mod rejects, and a
switch upstream declares and the build lacks means the same for a flag - both
are reported here, command by command.

Usage:
    python3 scripts/argument_audit.py [--inventory docs/commands-inventory.json]
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
    return spelling.lstrip("/").lower()


def spelling(row: dict) -> str:
    name = (row.get("name") or "").strip()
    if not name:
        return ""
    plain = name.lstrip("/")
    container = CONTAINERS.get(Path(row.get("file", "")).stem, "")
    return f"{container} {plain}" if container else plain


def upstream_shape(row: dict) -> tuple[list[str], list[str], list[str]]:
    """The required arguments, the switches and the value flags of a row."""
    required: list[str] = []
    switches: list[str] = []
    values: list[str] = []
    for parameter in row.get("params", []):
        annotation = parameter.get("ann") or {}
        kind = annotation.get("kind")
        if kind == "Arg":
            if annotation.get("def") in (None, ""):
                required.append(parameter.get("name") or parameter.get("type") or "?")
        elif kind == "Switch":
            switch = annotation.get("name")
            if switch:
                switches.append(str(switch))
        elif kind == "ArgFlag":
            flag = annotation.get("name")
            if flag:
                values.append(str(flag))
    return required, switches, values


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--inventory", default="docs/commands-inventory.json")
    parser.add_argument("--spec", default="docs/commands-spec.json")
    args = parser.parse_args()

    inventory = json.loads(Path(args.inventory).read_text())
    spec = json.loads(Path(args.spec).read_text())
    ours = {key(command["name"]): command for command in spec.get("commands", [])}

    # WorldEdit's word wins where both declare a spelling.
    shapes: dict[str, tuple[list[str], list[str], list[str]]] = {}
    rows: dict[str, dict] = {}
    for row in inventory:
        name = spelling(row)
        if not name:
            continue
        if row.get("source") == "WorldEdit" or key(name) not in shapes:
            shapes[key(name)] = upstream_shape(row)
            rows[key(name)] = row

    def row_of(_shapes: dict, command_name: str) -> dict:
        return rows.get(command_name, {})

    def upstream_optional(row: dict) -> list[str]:
        optional: list[str] = []
        for parameter in row.get("params", []):
            annotation = parameter.get("ann") or {}
            if annotation.get("kind") == "Arg" and annotation.get("def") not in (None, ""):
                optional.append(parameter.get("name") or "?")
        return optional

    problems = 0
    checked = 0
    for name, (required, switches, values) in sorted(shapes.items()):
        command = ours.get(name)
        if command is None:
            continue
        checked += 1
        mine = [argument.strip("[]") for argument in command.get("arguments", [])]
        mine_optional = [argument for argument in command.get("arguments", []) if argument.startswith("[")]
        flags = set(command.get("booleanFlags", [])) | set(command.get("valueFlags", []))
        # Upstream counts every positional argument it declares, required or with
        # a default; the build writes the optional ones between brackets.
        declared = len(required) + len(upstream_optional(row_of(shapes, name)))
        if declared > len(mine):
            problems += 1
            print(f"{command['name']}: upstream takes {declared} argument(s)"
                  f" ({required}{upstream_optional(row_of(shapes, name))}),"
                  f" the build takes {command.get('arguments', [])}")
        missing_switches = [switch for switch in switches if switch not in flags]
        if missing_switches:
            problems += 1
            print(f"{command['name']}: upstream switches {missing_switches} are not registered")
        missing_values = [flag for flag in values if flag not in flags]
        if missing_values:
            problems += 1
            print(f"{command['name']}: upstream value flags {missing_values} are not registered")
    print(f"commands compared: {checked}, argument mismatches: {problems}")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())

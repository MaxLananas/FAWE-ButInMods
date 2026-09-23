#!/usr/bin/env python3
"""Compares the switches FAWE declares with the ones this mod declares.

The inventory holds every upstream {@code @Switch} and {@code @ArgFlag} with the
container it lives in; the command spec is what the dispatcher actually
registered. A switch that is missing from the spec is a command line the mod
would reject, so the audit prints them grouped by command family. A flag declared
on both sides but with a different kind is a command line that means something
else: upstream {@code -f <format>} and a local {@code -f} with no value are not
the same switch, so those are reported as well.

Usage:
    python3 scripts/flag_audit.py [--inventory docs/commands-inventory.json]
                                  [--spec docs/commands-spec.json]
"""

from __future__ import annotations

import argparse
import json
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
    """Normalizes a command spelling so the two sides can be compared."""
    return spelling.lstrip("/").lower()


def family(row: dict) -> tuple[str, list[str]]:
    """The command family of an inventory row, and every spelling it answers to."""
    name = (row.get("name") or "").strip()
    if not name:
        return "", []
    container = CONTAINERS.get(Path(row.get("file", "")).stem, "")
    plain = name.lstrip("/")
    spellings = {name} if name.startswith("/") else set()
    if container:
        spellings.add(f"/{container} {plain}")
    for prefix in ("//", "/", ""):
        spellings.add(prefix + plain)
    return f"{container or '//'} {plain}".strip(), sorted(spellings)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--inventory", default="docs/commands-inventory.json")
    parser.add_argument("--spec", default="docs/commands-spec.json")
    arguments = parser.parse_args()

    inventory = json.loads(Path(arguments.inventory).read_text())
    spec = json.loads(Path(arguments.spec).read_text())
    entries = spec.get("commands", spec) if isinstance(spec, dict) else spec
    declared: dict[str, set[str]] = {}
    declared_values: dict[str, set[str]] = {}
    for entry in entries:
        flags = set(entry.get("booleanFlags", [])) | set(entry.get("valueFlags", []))
        for name in [entry.get("name", "")] + list(entry.get("aliases", [])):
            declared.setdefault(key(name), set()).update(flags)
            declared_values.setdefault(key(name), set()).update(entry.get("valueFlags", []))

    wanted: dict[str, set[str]] = {}
    wanted_values: dict[str, set[str]] = {}
    spellings_of: dict[str, list[str]] = {}
    for row in inventory:
        flags = set()
        value_flags = set()
        for parameter in row.get("params") or []:
            annotation = parameter.get("ann") or {}
            if annotation.get("kind") in ("Switch", "ArgFlag"):
                name = annotation.get("name", "").strip("'").lstrip("-")
                if name:
                    flags.add(name)
                    if annotation.get("kind") == "ArgFlag":
                        value_flags.add(name)
        if not flags:
            continue
        name, spellings = family(row)
        wanted.setdefault(name, set()).update(flags)
        wanted_values.setdefault(name, set()).update(value_flags)
        spellings_of.setdefault(name, [])
        for spelling in spellings:
            if spelling not in spellings_of[name]:
                spellings_of[name].append(spelling)

    # Sub-commands of a container (schem, anvil, history, snapshot, tool, we)
    # read their flags from the container entry, so the container's set counts.
    containers = {"schem": ("//schem", "/schem"), "anvil": ("/anvil",), "history": ("/history", "//history"),
                  "snapshot": ("/snapshot",), "tool": ("/tool", "//tool"), "we": ("/we",),
                  "brush": ("//brush", "/brush")}
    missing: dict[str, set[str]] = {}
    for name in sorted(wanted):
        known: set[str] = set()
        for spelling in spellings_of[name]:
            known |= declared.get(key(spelling), set())
        head = name.split(" ", 1)[0].lstrip("/")
        for container in containers.get(head, ()):
            known |= declared.get(key(container), set())
        absent = {flag for flag in wanted[name] if flag not in known}
        if absent:
            missing[name] = absent

    # A flag that takes a value upstream cannot be declared as a plain switch.
    wrong_kind: dict[str, list[str]] = {}
    for name, flags in sorted(wanted_values.items()):
        local: set[str] = set()
        for spelling in spellings_of.get(name, []):
            local |= declared_values.get(key(spelling), set())
        head = name.split(" ", 1)[0].lstrip("/")
        for container in containers.get(head, ()):
            local |= declared_values.get(key(container), set())
        absent_values = sorted(flag for flag in flags if flag not in local)
        if absent_values and name not in missing:
            wrong_kind[name] = absent_values

    print(f"upstream declarations with switches: {len(wanted)}, "
          f"command families missing at least one flag: {len(missing)}, "
          f"flags missing: {sum(len(f) for f in missing.values())}")
    for name, flags in missing.items():
        print(f"  {name:<28} missing: {', '.join(sorted(flags))}")
    print(f"families whose value flags are not declared as value flags: {len(wrong_kind)}")
    for name, flags in wrong_kind.items():
        print(f"  {name:<28} should take a value: {', '.join(flags)}")


if __name__ == "__main__":
    main()

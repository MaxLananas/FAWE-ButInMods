#!/usr/bin/env python3
"""Generates the command tables that keep the engine in sync with WorldEdit/FAWE.

Inputs (docs/):
  * commands-inventory.json - every @Command declaration in WorldEdit 7.3.17 and
    FastAsyncWorldEdit main: name, aliases, description, declaring file.
  * commands-spec.json - what the engine registers right now, written by
    `./gradlew :core:genDocs` from the live registry.

Outputs (core/src/main/java/com/maxlananas/fawebim/core/command/):
  * SubCommandTable.java - spellings that are aliases of a command that exists
    (`0` -> `/air`, `blob` -> `/brush rock`, ...), so a name is never registered
    as a dead entry when the behaviour is already there.
  * StubTable.java - names nothing implements yet, with their upstream
    description, so the command surface stays complete while the port continues.

Workflow after adding or removing a command:
    ./gradlew :core:genDocs
    python3 scripts/generate_command_tables.py
"""

from __future__ import annotations

import json
import re
from pathlib import Path
from collections import OrderedDict

ROOT = Path(__file__).resolve().parent.parent
DOCS = ROOT / "docs"
COMMAND_DIR = ROOT / "core/src/main/java/com/maxlananas/fawebim/core/command"

# File -> container literal. Sub-commands of a container are reached by typing
# the container first (`/brush sphere`, `/tool tree`, `/schem load`, ...).
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

# CraftScript needs a JavaScript engine, which the mod does not ship; the name is
# deliberately not registered so it cannot be mistaken for a working command.
SKIP = {".s", "s", "0.s"}

LITERAL = re.compile(r"^[a-z0-9_.+-]+$")


def plain(name: str) -> str:
    return (name or "").strip().lstrip("/")


# A command name must be a single token a Brigadier literal can hold: `/`,
# `,`, `*` and `.s` are aliases or CraftScript hooks, not commands.
LITERAL = re.compile(r"^[a-z0-9_.+-]+$")


def is_literal(name: str) -> bool:
    bare = plain(name)
    return bool(LITERAL.match(bare)) and not bare.startswith("-")


def spellings(name: str) -> list[str]:
    """Every way the engine may hold a command: `//name`, `/name`, `name`."""
    bare = plain(name)
    if not bare:
        return []
    return ["//" + bare, "/" + bare, bare]


def is_literal(name: str) -> bool:
    """True when the name can be a Brigadier literal of its own."""
    bare = plain(name)
    return bool(LITERAL.match(bare)) and not bare.startswith("-") and bare not in SKIP


def java_string(value: str) -> str:
    return '"' + value.replace("\\", "\\\\").replace('"', '\\"') + '"'


def resolve(registered: dict[str, dict], candidates: list[str]) -> dict | None:
    """First registered entry among the spellings, preferring implemented ones."""
    for candidate in candidates:
        entry = registered.get(candidate)
        if entry and entry["status"] == "implemented":
            return entry
    for candidate in candidates:
        entry = registered.get(candidate)
        if entry and entry["status"] == "alias":
            return entry
    return None


def argument_names(entry: dict | None) -> set[str]:
    """Literal sub-command names an entry dispatches from its own arguments.

    WorldEdit declares `/schem <list|save|load|delete>` as one command whose
    first argument picks the action, so those names are implemented even though
    no entry is registered for them.
    """
    names: set[str] = set()
    if entry is None:
        return names
    for argument in entry.get("arguments") or []:
        for token in re.split(r"[|/]", argument):
            token = token.strip().strip("[]()<>").strip()
            if LITERAL.match(token) and not token.startswith("-"):
                names.add(token)
    return names


def family_keys(row: dict) -> list[str]:
    """The spellings a declaration answers to, in dispatch order."""
    container = CONTAINERS.get(Path(row.get("file", "")).name)
    keys: list[str] = []
    for candidate in [row.get("name")] + list(row.get("aliases") or []):
        if not is_literal(candidate or ""):
            continue
        bare = plain(candidate)
        if container:
            keys.extend([f"/{container} {bare}", f"{container} {bare}"])
        else:
            keys.extend(spellings(bare))
    return keys


def generate(inventory: list[dict], supported: dict[str, dict]) -> tuple[list, list]:
    """Every declared name either routes to a command that exists, or is a stub."""
    routes: OrderedDict[str, str] = OrderedDict()
    stubs: OrderedDict[str, str] = OrderedDict()
    for row in inventory:
        bare = plain(row.get("name"))
        if not is_literal(bare):
            continue
        description = row.get("desc") or ""
        container = CONTAINERS.get(Path(row.get("file", "")).name)
        if container:
            # A sub-command is only reachable through its container, so it never
            # becomes a top-level command. It is either handled by the container
            # (as a registered path or through its own arguments), an alias of a
            # sub-command that exists (`/brush bb` for `/brush blendball`), or a
            # sub-command stub.
            container_entry = resolve(supported, spellings(container))
            dispatched = argument_names(container_entry)
            target = resolve(supported, [f"/{container} {plain(c)}"
                                         for c in [row.get("name")] + list(row.get("aliases") or [])
                                         if is_literal(c or "")])
            for candidate in [row.get("name")] + list(row.get("aliases") or []):
                if not is_literal(candidate or ""):
                    continue
                name = plain(candidate)
                if name in dispatched:
                    continue
                path = f"/{container} {name}"
                if any(spelling in supported for spelling in spellings(path)):
                    continue
                if target is not None and target["name"] != path:
                    routes.setdefault(path, target["name"])
                    continue
                stubs.setdefault(path, description)
            continue
        candidates = [row.get("name")] + list(row.get("aliases") or [])
        keys = [key for candidate in candidates if is_literal(candidate or "")
                for key in spellings(candidate)]
        target = resolve(supported, keys)
        anchor = target["name"] if target is not None else bare
        if target is None:
            stubs.setdefault(bare, description)
        for candidate in candidates:
            if not is_literal(candidate or ""):
                continue
            spelling = plain(candidate)
            if spelling == plain(anchor):
                continue
            if any(key in supported for key in spellings(spelling)):
                continue
            routes.setdefault(spelling, anchor)
    return list(routes.items()), list(stubs.items())


def write_routes(routes: list[tuple[str, str]]) -> None:
    body = [
        "package com.maxlananas.fawebim.core.command;",
        "",
        "/**",
        " * Alias routes generated by {@code scripts/generate_command_tables.py}.",
        " *",
        " * <p>WorldEdit declares a command once and gives it several spellings, and",
        " * declares brush, tool, schematic, snapshot and super-pickaxe sub-commands",
        " * inside their container. Each pair is {@code spelling}, {@code command}:",
        " * the spelling runs the command that already implements the behaviour, so no",
        " * duplicate implementation is needed for names such as {@code 0} ({@code /air})",
        " * or {@code blob} ({@code /brush rock}).</p>",
        " *",
        " * <p>Do not edit by hand: re-run the script after changing the command set.</p>",
        " */",
        "final class SubCommandTable {",
        "",
        "    private SubCommandTable() {",
        "    }",
        "",
        "    static final String[] PAIRS = {",
    ]
    for alias, target in routes:
        body.append(f"            {java_string(alias)}, {java_string(target)},")
    body += ["    };", "}", ""]
    (COMMAND_DIR / "SubCommandTable.java").write_text("\n".join(body))


def write_stubs(stubs: list[tuple[str, str]]) -> None:
    body = [
        "package com.maxlananas.fawebim.core.command;",
        "",
        "/**",
        " * Command names that still have no implementation, generated by",
        " * {@code scripts/generate_command_tables.py}.",
        " *",
        " * <p>Every name WorldEdit 7.3.17 and FastAsyncWorldEdit declare is registered,",
        " * so nothing is missing from the command surface; this table holds the ones",
        " * that answer with a clear \"not implemented\" message. It shrinks on its own as",
        " * command classes take the names over.</p>",
        " *",
        " * <p>Do not edit by hand: re-run the script after changing the command set.</p>",
        " */",
        "final class StubTable {",
        "",
        "    private StubTable() {",
        "    }",
        "",
        "    /** {@code name, description} pairs. */",
        "    static final String[] ENTRIES = {",
    ]
    for name, description in stubs:
        body.append(f"            {java_string(name)}, {java_string(description)},")
    body += ["    };", "}", ""]
    (COMMAND_DIR / "StubTable.java").write_text("\n".join(body))


def main() -> None:
    inventory = json.loads((DOCS / "commands-inventory.json").read_text())
    spec = json.loads((DOCS / "commands-spec.json").read_text())
    supported: dict[str, dict] = {}
    for command in spec["commands"]:
        # Alias rows are the routes this script generates, so they are not
        # counted as implementations: otherwise the table it writes decides what
        # the next run considers supported and the output never settles.
        if command["status"] != "implemented":
            continue
        supported[command["name"]] = command
        for alias in command.get("aliases") or []:
            supported.setdefault(alias, command)

    routes, stubs = generate(inventory, supported)
    write_routes(routes)
    write_stubs(stubs)
    print(f"SubCommandTable: {len(routes)} spellings routed to an existing command")
    print(f"StubTable: {len(stubs)} names still to port")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Compares the switches FAWE declares with the ones this mod declares.

The inventory holds every upstream {@code @Switch} and {@code @ArgFlag} with the
container it lives in; the command spec is what the dispatcher actually
registered. A switch that is missing from the spec is a command line the mod
would reject, so the audit prints them grouped by command family. A flag declared
on both sides but with a different kind is a command line that means something
else: upstream {@code -f <format>} and a local {@code -f} with no value are not
the same switch, so those are reported as well.

A flag can also be declared and still do nothing: the brush table carries the
signature of every brush, and a brush whose factory never reads one of its flags
accepts the command line and ignores it. That cross-check is the last thing this
audit prints.

Usage:
    python3 scripts/flag_audit.py [--inventory docs/commands-inventory.json]
                                  [--spec docs/commands-spec.json]
                                  [--brush-table core/src/main/java/.../BrushTable.java]
                                  [--brush-factory core/src/main/java/.../BrushFactory.java]
"""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

BRUSH_TABLE = "core/src/main/java/com/maxlananas/fawebim/core/command/BrushTable.java"

# Parameters a brush takes as a mask: -m <mask> fills one of these, and a mask
# declared as a positional argument is one of these too.
MASK_PARAMETERS = {"mask", "sourceMask"}
BRUSH_FACTORY = "core/src/main/java/com/maxlananas/fawebim/core/brush/BrushFactory.java"

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


def brush_mask_arguments(table: str) -> dict[str, set[str]]:
    """The mask parameters every brush row declares as a positional argument."""
    masks: dict[str, set[str]] = {}
    for row in re.findall(r"\{\"([^\"]*)\",\s*\"[^\"]*\",\s*\"([^\"]*)\",", table):
        name, arguments = row
        declared = {argument.split("=")[0].strip() for argument in arguments.split("|")
                    if argument.split("=")[0].strip() in MASK_PARAMETERS}
        if declared:
            masks[name] = declared
    return masks


def brush_rows(table: str) -> list[tuple[str, str, dict[str, str]]]:
    """The name, the declared letters and the value flags of every brush row.

    A value flag is written {@code parameter:letter} when the parameter it fills
    is not spelled like the letter, and the returned mapping keeps that pair so
    the audit can check that the value really reaches the parameter.
    """
    rows = []
    for row in re.findall(r"\{\"([^\"]*)\",\s*\"[^\"]*\",\s*\"[^\"]*\",\s*\"([^\"]*)\",\s*\"([^\"]*)\",",
                          table):
        name, switches, value_flags = row
        letters = {letter.strip() for letter in switches.split(",") if letter.strip()}
        letters |= {entry.rsplit(":", 1)[-1].strip() for entry in value_flags.split(",") if entry.strip()}
        pairs = {}
        for entry in value_flags.split(","):
            entry = entry.strip()
            if not entry:
                continue
            parameter, _, letter = entry.partition(":")
            pairs[letter or parameter] = parameter
        rows.append((name, sorted(letters), pairs))
    return rows


def factory_blocks(factory: str) -> dict[str, str]:
    """The body of every brush case of the factory, keyed by brush name."""
    starts = [(match.start(), match.group(1))
              for match in re.finditer(r'case ((?:"[a-z]+",?\s*)+)->', factory)]
    blocks: dict[str, str] = {}
    for index, (position, names) in enumerate(starts):
        end = starts[index + 1][0] if index + 1 < len(starts) else len(factory)
        body = factory[position:end]
        # The same name also appears in the switches that translate an alias to
        # its canonical brush, whose case is a bare string return; keep the body
        # that actually builds the brush.
        if re.search(r"case [^-]*->\s*\"[^\"]*\";", body.split("\n")[0]):
            continue
        for name in re.findall(r'"([a-z]+)"', names):
            if name not in blocks or len(body) > len(blocks[name]):
                blocks[name] = body
    return blocks


def parameters_read(text: str) -> set[str]:
    """Every BrushParameters parameter the given code reads, by its own name."""
    read = set()
    if "flagMask()" in text:
        read.add("mask")
    for match in re.finditer(r'maskValue\("([\w]+)"\)', text):
        read.add(match.group(1))
    for match in re.finditer(r'(?:flag|switchOn|string|integer|number|expression|intValue|doubleValue)\('
                             r'\s*"([\w]+)"', text):
        read.add(match.group(1))
    return read


def flags_read(text: str) -> set[str]:
    """Every flag letter the given code reads off a BrushParameters."""
    read = set()
    if "flagMask()" in text or "maskValue(" in text:
        read.add("m")
    for match in re.finditer(r'(?:flag|switchOn|string|integer|number|expression|intValue|doubleValue)\('
                             r'\s*"([\w]+)"', text):
        # A parameter spelled like the letter it is filled from, e.g. -a.
        read.add(match.group(1))
    for match in re.finditer(r'integer\("(snowBlockCount)"', text):
        # -l fills a parameter whose name is not its letter.
        read.add("l")
    return read


def brush_flag_audit(table_path: str, factory_path: str) -> list[str]:
    """Reports brush flags the factory declares but never reads."""
    table = Path(table_path)
    factory = Path(factory_path)
    if not table.exists() or not factory.exists():
        print(f"brush flag audit skipped: {table} or {factory} is missing")
        return []
    blocks = factory_blocks(factory.read_text())
    mask_arguments = brush_mask_arguments(table.read_text())
    unread: list[str] = []
    for name, letters, pairs in brush_rows(table.read_text()):
        block = blocks.get(name)
        if block is None:
            unread.append(f"{name} (no factory case)")
            continue
        # A case may delegate to a helper that reads the flags for it.
        body = block
        for helper in re.findall(r'\b(\w+)\(parameters\b', body):
            match = re.search(rf"private static [\w.<>\[\]]+ {helper}\([^)]*\) \{{", factory.read_text())
            if match:
                helper_end = factory.read_text().find("\n    }", match.end())
                body += factory.read_text()[match.start():helper_end]
        read = flags_read(body)
        if name == "gravity":
            # -h is WorldEdit's height and FAWE's flag at once; the factory reads
            # the height under its parameter name and the flag under its letter.
            read.add("h")
        missing = [letter for letter in letters if letter not in read]
        if missing:
            unread.append(f"{name} (never reads {', '.join('-' + m for m in missing)})")
            continue
        # A flag spelled -m on the command line only reaches the factory if the
        # table calls the parameter the factory reads, so a brush whose -m fills
        # "sourceMask" and one whose -m fills "mask" are different rows.
        parameters = parameters_read(body)
        for letter, parameter in pairs.items():
            if parameter in parameters:
                continue
            unread.append(f"{name} (-{letter} fills '{parameter}', which the factory never reads)")
        for parameter in mask_arguments.get(name, set()):
            if parameter in parameters:
                continue
            unread.append(f"{name} (declares the mask argument '{parameter}', which the factory never reads)")
    print(f"brushes whose factory never reads a flag: {len(unread)}")
    for entry in unread:
        print(f"  {entry}")
    return unread


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--inventory", default="docs/commands-inventory.json")
    parser.add_argument("--spec", default="docs/commands-spec.json")
    parser.add_argument("--brush-table", default=BRUSH_TABLE)
    parser.add_argument("--brush-factory", default=BRUSH_FACTORY)
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

    unread_brushes = brush_flag_audit(arguments.brush_table, arguments.brush_factory)

    if missing or wrong_kind or unread_brushes:
        raise SystemExit(1)


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Extracts the WorldEdit / FastAsyncWorldEdit command inventory.

Reads the upstream checkouts, keeps every live ``@Command`` declaration (commented
declarations are ignored — that is how FAWE ships its disabled CFI commands) and
writes the rows the documentation and the alias table are generated from.

Usage:
    python3 scripts/extract_command_inventory.py \\
        --worldedit /path/to/WorldEdit-7.3.17 \\
        --fawe /path/to/FastAsyncWorldEdit-main \\
        --out reference/commands-inventory.json

The CLI platform (``worldedit-cli``) is skipped: its commands only exist for the
standalone command line tool, not for any in-game platform.
"""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

SKIPPED_MODULES = ("worldedit-cli", "worldedit-bukkit", "worldedit-sponge")

COMMAND = re.compile(r"@Command\((?P<body>.*?)@(?:CommandPermissions|Logging|SynchronousSettingExpected"
                     r"|Confirm|Switch|Arg|Optional|Selection|InjectedValueAccess|WorldEditException)"
                     r"|@Command\((?P<last>.*?)\)\s*\n\s*(?:public|protected|void)\b", re.S)


def strip_comments(text: str) -> str:
    """Removes ``//`` and ``/* */`` comments so disabled commands stay disabled."""
    without_block = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return "\n".join(line for line in without_block.splitlines() if not line.lstrip().startswith("//"))


def top_level_split(text: str) -> list[str]:
    """Splits a parameter list on the commas that are not inside a generic."""
    parts: list[str] = []
    depth = 0
    current = ""
    in_string = False
    for char in text:
        if char == '"':
            in_string = not in_string
        if not in_string:
            if char in "<([":
                depth += 1
            elif char in ">)]":
                depth -= 1
            elif char == "," and depth == 0:
                parts.append(current)
                current = ""
                continue
        current += char
    if current.strip():
        parts.append(current)
    return parts


def match_braces(text: str, start: int) -> int:
    """Index just past the bracket group ``text[start]`` opens, quotes included."""
    depth = 0
    in_string: str | None = None
    index = start
    while index < len(text):
        char = text[index]
        if in_string:
            if char == "\\":
                index += 2
                continue
            if char == in_string:
                in_string = None
        elif char in "\"'":
            in_string = char
        elif char in "([{":
            depth += 1
        elif char in ")]}":
            depth -= 1
            if depth == 0:
                return index + 1
        index += 1
    return len(text)


def skip_annotations(text: str, start: int) -> int:
    """Skips the ``@Annotation(...)`` groups between two points in the source."""
    index = start
    while True:
        match = re.match(r"\s*@\w+", text[index:])
        if not match:
            return index
        index += match.end()
        if text[index:index + 1] == "(":
            index = match_braces(text, index)
        while text[index:index + 1] == "\n":
            index += 1


def annotation_of(parameter: str) -> dict | None:
    """The @Arg / @Switch / @ArgFlag / @Optional annotation on a parameter."""
    match = re.search(r"@(\w+)\s*\(", parameter)
    if not match:
        return None
    body = parameter[match.end(): match_braces(parameter, match.end() - 1) - 1]
    kind = match.group(1)
    entry: dict = {"kind": kind}
    for key in ("name", "desc", "def"):
        value = re.search(rf"{key}\s*=\s*" + r"('([^']*)'|\"([^\"]*)\")", body)
        if value:
            entry[key] = value.group(2) if value.group(2) is not None else value.group(3)
    if kind == "Arg" and "name" not in entry:
        entry["name"] = ""
    return entry


def without_annotations(text: str) -> str:
    """Drops every annotation, using the same rules that find them."""
    result = ""
    index = 0
    while index < len(text):
        match = re.match(r"\s*@\w+", text[index:])
        if match:
            index += match.end()
            if text[index:index + 1] == "(":
                index = match_braces(text, index)
            result += " "
            continue
        result += text[index]
        index += 1
    return result


def parameter_of(text: str) -> dict:
    """Type and name of a parameter, with the annotation that precedes it."""
    cleaned = re.sub(r"@\w+\s*\(.*?\)", " ", text, flags=re.S).strip()
    cleaned = re.sub(r"\s+", " ", cleaned)
    match = re.match(r"(?:(final)\s+)?([\w<>,.?\[\] ]+?)\s+(\w+)\s*$", cleaned)
    if match:
        return {"type": match.group(2).strip(), "name": match.group(3)}
    return {"type": cleaned[:60] or "?", "name": "?"}


def declarations_of(path: Path) -> list[dict]:
    text = strip_comments(path.read_text(errors="replace"))
    rows: list[dict] = []
    for match in re.finditer(r"@Command\s*\(", text):
        open_paren = match.end() - 1
        close = match_braces(text, open_paren) - 1
        body = text[open_paren + 1: close]
        # An annotated command can be followed by more annotations and then the
        # method; walk to the method's parameter list.
        # The remaining annotations sit between @Command and the method; they are
        # skipped so the signature is read from the method itself.
        signature = skip_annotations(text, close + 1)
        # \s+ after throws: the exception list often starts on the next line, and
        # a literal space there would make the match run into the next method.
        method = re.search(r"\b(?P<name>\w+)\s*\((?P<params>.*?)\)\s*(?:throws\s+[\w, .]+)?\s*\{",
                           text[signature:], re.S)
        if not method:
            continue
        name = re.search(r"name\s*=\s*\"([^\"]*)\"", body)
        if not name:
            continue
        aliases = re.search(r"aliases\s*=\s*\{(.*?)\}", body, re.S)
        row = {
            "source": "",
            "file": "",
            "package": "",
            "method": method.group("name"),
            "name": name.group(1),
            "aliases": re.findall(r'"([^"]*)"', aliases.group(1)) if aliases else [],
            "desc": (re.search(r"desc\s*=\s*\"([^\"]*)\"", body) or [None, ""])[1],
            "descFooter": (re.search(r"descFooter\s*=\s*\"([^\"]*)\"", body) or [None, ""])[1],
            "flags": (re.search(r"flags\s*=\s*\{?\s*\"([^\"]*)\"", body) or [None, ""])[1],
            "help": (re.search(r"help\s*=\s*\"([^\"]*)\"", body) or [None, ""])[1],
            "params": [],
        }
        for parameter in top_level_split(method.group("params")):
            if not parameter.strip():
                continue
            item = parameter_of(parameter)
            item["ann"] = annotation_of(parameter)
            row["params"].append(item)
        rows.append(row)
    return rows


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--worldedit", required=True)
    parser.add_argument("--fawe", required=True)
    parser.add_argument("--out", required=True)
    args = parser.parse_args()

    rows: list[dict] = []
    for label, root in (("WorldEdit", Path(args.worldedit)), ("FAWE", Path(args.fawe))):
        for path in sorted(root.rglob("*.java")):
            if any(module in path.parts for module in SKIPPED_MODULES) or "src/test" in str(path):
                continue
            for row in declarations_of(path):
                row["source"] = label
                relative = path.relative_to(root.parent)
                row["file"] = str(relative)
                package = re.search(r"package\s+([\w.]+);", path.read_text(errors="replace"))
                row["package"] = package.group(1) if package else ""
                rows.append(row)

    rows.sort(key=lambda row: (row["name"], row["file"]))
    Path(args.out).write_text(json.dumps(rows, indent=1) + "\n", encoding="utf-8")
    names = {row["name"] for row in rows}
    print(f"{len(rows)} declarations, {len(names)} distinct names -> {args.out}")


if __name__ == "__main__":
    main()

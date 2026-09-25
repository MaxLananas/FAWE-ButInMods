#!/usr/bin/env python3
"""Fails on a member declared twice in the same type of the Fabric adapter.

The engine core is compiled and tested by the local build; the adapter is only
compiled by Gradle, so a method or a field declared twice - something javac
refuses outright - would otherwise cost a full CI run to surface.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ADAPTER = ROOT / "fabric" / "src" / "main" / "java"

TYPE = re.compile(r"\b(?:class|interface|enum|record)\s+(\w+)")
MEMBER = re.compile(
    r"^\s*(?:\w[\w.<>\[\],?\s]*\s+)?(\w+)\s*\((?P<params>[^)]*)\)\s*"
    r"(?P<end>\{|;|throws\b)")
FIELD = re.compile(r"^\s*(?:[\w.<>\[\],?]+\s+)+(\w+)\s*(?:=[^;]*)?;")


def code_without_text(source: str) -> list[str]:
    """The lines with comments and string literals blanked out."""
    out = []
    block = False
    for line in source.splitlines():
        text = ""
        index = 0
        while index < len(line):
            two = line[index:index + 2]
            if block:
                if two == "*/":
                    block = False
                    index += 2
                else:
                    index += 1
                continue
            if two == "/*":
                block = True
                index += 2
                continue
            if two == "//":
                break
            char = line[index]
            if char in "\"'":
                index += 1
                while index < len(line):
                    if line[index] == "\\":
                        index += 2
                        continue
                    if line[index] == char:
                        index += 1
                        break
                    index += 1
                text += " "
                continue
            text += char
            index += 1
        out.append(text)
    return out


def members(path: Path) -> dict[str, list[tuple[str, int]]]:
    """Every declared member, per enclosing type, as signature -> line numbers."""
    lines = code_without_text(path.read_text(encoding="utf-8"))
    found: dict[str, list[tuple[str, int]]] = {}
    stack: list[tuple[str, int]] = []
    depth = 0
    index = 0
    while index < len(lines):
        line = lines[index]
        if not stack or stack[-1][1] != depth:
            match = TYPE.search(line)
            if match and "{" in line:
                stack.append((match.group(1), depth + 1))
            elif stack and depth < stack[-1][1]:
                stack.pop()
        if stack and depth == stack[-1][1]:
            signature = line.rstrip()
            if "(" in line:
                # A parameter list may wrap: take the lines up to its closing bracket.
                while signature.count("(") > signature.count(")") and index + 1 < len(lines):
                    index += 1
                    signature += " " + lines[index].strip()
                match = MEMBER.match(signature)
                if match and not signature.lstrip().startswith(("if", "for", "while", "switch", "return")):
                    params = re.sub(r"\s+", " ", match.group("params")).strip()
                    key = match.group(1) + "(" + params + ")"
                    found.setdefault(stack[-1][0], []).append((key, index + 1))
            else:
                match = FIELD.match(line)
                if match:
                    found.setdefault(stack[-1][0], []).append((match.group(1), index + 1))
        depth += line.count("{") - line.count("}")
        index += 1
    return found


def main() -> int:
    problems = 0
    for path in sorted(ADAPTER.rglob("*.java")):
        for owner, seen in members(path).items():
            first: dict[str, int] = {}
            for key, line in seen:
                if key in first:
                    print(f"{path.relative_to(ROOT)}:{line}: {owner} declares {key} twice "
                          f"(first at line {first[key]})")
                    problems += 1
                else:
                    first[key] = line
    print(f"member audit: {problems} duplicated declaration(s)")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())

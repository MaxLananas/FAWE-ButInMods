#!/usr/bin/env python3
"""Checks that every line the mod writes to a player carries a colour.

The engine formats its answers with the legacy colour codes, so every call that
sends text to a player - a result line, an error, a hint, a clickable line - has
to hand out text that already holds a code, either written in the literal or
built by Msg. This walks both source trees and reports the calls that do not,
which is what makes "every answer is coloured" a check rather than a promise.

    python3 scripts/colour_audit.py
"""
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
# The calls that reach a player. Logger lines are not chat, and the settings
# screen draws its own status line in the colour it picks for good and bad news,
# so neither is in scope.
CALLS = re.compile(r"(?<![\w.])(message|error|info|status|link|commandLink|suggestLink)\s*\(")
SOURCES = ("core/src/main/java", "fabric/src/main/java")
IGNORED = ("fabric/src/main/java/com/maxlananas/fawebim/fabric/client/",)
# A declaration, not a call: the parameter list of a method that sends text, or
# a method whose name is one of the calls and whose return type comes before it.
DECLARATION = re.compile(r"^\s*(String|Msg|Component|boolean|int|long|void|Actor)\b")
RETURN_TYPE = re.compile(r"\b(String|Msg|Component|boolean|int|long|void|Actor)\s+$")


def argument_of(text, open_paren):
    """The text between a call's parentheses, nesting respected."""
    depth = 1
    index = open_paren
    while index < len(text) and depth:
        if text[index] == "(":
            depth += 1
        elif text[index] == ")":
            depth -= 1
        index += 1
    return text[open_paren:index]


def audit(path):
    relative = str(path.relative_to(REPO))
    if relative.startswith(IGNORED):
        return []
    text = path.read_text(encoding="utf-8")
    problems = []
    for match in CALLS.finditer(text):
        argument = argument_of(text, match.end())
        if DECLARATION.match(argument) or RETURN_TYPE.search(
                text[max(0, match.start() - 32):match.start()]):
            continue
        # An exception built here is printed by whoever catches it, in colour.
        if re.search(r"\bthrow\s+$", text[max(0, match.start() - 12):match.start()]):
            continue
        if "\u00a7" in argument or "\\u00a7" in argument or "Msg" in argument:
            continue
        line = text[:match.start()].count("\n") + 1
        problems.append(f"{relative}:{line}: {' '.join(argument.split())[:100]}")
    return problems


def main():
    problems = []
    for source in SOURCES:
        for path in sorted((REPO / source).rglob("*.java")):
            problems.extend(audit(path))
    for problem in problems:
        print(problem)
    print(f"chat lines without a colour: {len(problems)}")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())

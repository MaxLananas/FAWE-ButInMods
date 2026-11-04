#!/usr/bin/env python3
"""Static audit of the engine and the adapter.

Every rule below is a defect class that has bitten this project, or that a
reviewer of a 60k-line port has to check by hand otherwise.  The audit prints
one line per hit, with the file and line, so each one can be read and either
fixed or dismissed; nothing here edits the sources.

Usage: python3 tools/audit.py [--rule NAME] [--path DIR]
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SOURCES = [ROOT / "core/src", ROOT / "fabric/src"]

# --------------------------------------------------------------------------
# source loading
# --------------------------------------------------------------------------


class Source:
    def __init__(self, path: Path):
        self.path = path
        self.rel = path.relative_to(ROOT).as_posix()
        self.text = path.read_text(encoding="utf-8", errors="replace")
        self.lines = self.text.splitlines()
        # Comments and string literals are blanked out for the rules that must
        # not fire on prose; the offsets are kept so reported lines still match.
        self.code = blank_non_code(self.text)

    def line_of(self, offset: int) -> int:
        return self.text.count("\n", 0, offset) + 1


def blank_non_code(text: str) -> str:
    out = []
    i, n = 0, len(text)
    state = "code"
    while i < n:
        c = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if state == "code":
            if c == "/" and nxt == "/":
                state = "line"
                out.append("  ")
                i += 2
                continue
            if c == "/" and nxt == "*":
                state = "block"
                out.append("  ")
                i += 2
                continue
            if c == '"':
                state = "string"
                out.append(" ")
                i += 1
                continue
            if c == "'":
                # char literal, or the apostrophe of a French comment (already
                # blanked above); only treat it as a literal when it closes.
                close = text.find("'", i + 1, i + 8)
                if close != -1 and "\n" not in text[i:close]:
                    out.append(" " * (close - i + 1))
                    i = close + 1
                    continue
            out.append(c)
            i += 1
            continue
        if state == "line":
            if c == "\n":
                state = "code"
                out.append("\n")
            else:
                out.append(" ")
            i += 1
            continue
        if state == "block":
            if c == "*" and nxt == "/":
                state = "code"
                out.append("  ")
                i += 2
                continue
            out.append("\n" if c == "\n" else " ")
            i += 1
            continue
        if state == "string":
            if c == "\\":
                out.append("  ")
                i += 2
                continue
            if c == '"':
                state = "code"
            out.append("\n" if c == "\n" else " ")
            i += 1
            continue
    return "".join(out)


def load() -> list[Source]:
    sources = []
    for base in SOURCES:
        for path in sorted(base.rglob("*.java")):
            sources.append(Source(path))
    return sources


# --------------------------------------------------------------------------
# rules
# --------------------------------------------------------------------------

HITS: list[tuple[str, str, int, str]] = []


def hit(rule: str, source: Source, offset: int, message: str) -> None:
    HITS.append((rule, source.rel, source.line_of(offset), message))


BOXED = {"Integer", "Long", "Short", "Byte", "Boolean", "Character", "Double", "Float"}


def rule_dead_private_method(sources, index):
    for source in sources:
        for m in re.finditer(r"^[ \t]*private (?:(?:static|final|synchronized) )*"
                             r"(?:<[^>]+> )?([A-Za-z_][\w<>\[\], ?.]*) ([a-z]\w*)\s*\(",
                             source.code, re.M):
            name = m.group(2)
            uses = len(re.findall(r"\b" + re.escape(name) + r"\s*\(", index))
            decl = len(re.findall(r"^[ \t]*(?:public |private |protected |static |final |synchronized )*"
                                  r"[\w<>\[\], ?.]+ +" + re.escape(name) + r"\s*\(", source.code, re.M))
            if uses == 0 and decl == 1:
                hit("dead-method", source, m.start(), f"private {name}() is never called")


def rule_dead_private_field(sources, index):
    for source in sources:
        for m in re.finditer(r"^[ \t]*private (?:(?:static|final|volatile|transient) )*"
                             r"([A-Za-z_][\w<>\[\], ?.]*) ([a-z]\w*)\s*(?:=|;)", source.code, re.M):
            name = m.group(2)
            if name.endswith("__"):
                continue
            uses = len(re.findall(r"\b" + re.escape(name) + r"\b", index))
            if uses <= 1:
                hit("dead-field", source, m.start(), f"private field {name} is never read")


def rule_empty_catch(sources, index):
    for source in sources:
        for m in re.finditer(r"catch \(([^)]*)\)\s*\{", source.code):
            start = m.end()
            depth, i = 1, start
            while i < len(source.code) and depth:
                if source.code[i] == "{":
                    depth += 1
                elif source.code[i] == "}":
                    depth -= 1
                i += 1
            body = source.code[start:i - 1].strip()
            if not body:
                hit("empty-catch", source, m.start(), f"catch ({m.group(1)}) does nothing")


def rule_locale(sources, index):
    for source in sources:
        for m in re.finditer(r"\.(toLowerCase|toUpperCase)\(\s*\)", source.code):
            hit("locale", source, m.start(), f"{m.group(1)}() depends on the default locale")


def rule_boxed_identity(sources, index):
    for source in sources:
        for m in re.finditer(r"\b([A-Za-z_]\w*)\s*==\s*([A-Za-z_]\w*)\b", source.code):
            a, b = m.group(1), m.group(2)
            if a in BOXED or b in BOXED:
                hit("boxed-identity", source, m.start(), f"{a} == {b} compares boxed values")


def rule_int_overflow(sources, index):
    # A coordinate or a size multiplied by a size, kept in an int: the volumes
    # of an edit overflow past ~46k blocks on a side.
    for source in sources:
        for m in re.finditer(r"\b([a-z]\w*)\s*\*\s*([a-z]\w*)\b", source.code):
            a, b = m.group(1), m.group(2)
            if a in ("size", "volume", "count", "length", "width", "height", "depth") or \
               b in ("size", "volume", "count", "length", "width", "height", "depth"):
                line = source.lines[source.line_of(m.start()) - 1]
                if "long" not in line:
                    hit("int-overflow", source, m.start(), f"{a} * {b} is computed in an int")


def rule_parse_without_guard(sources, index):
    for source in sources:
        for m in re.finditer(r"Integer\.parseInt\(|Long\.parseLong\(|Double\.parseDouble\(", source.code):
            # the enclosing 15 lines decide: a try nearby, or a caller that does
            line = source.line_of(m.start())
            window = "\n".join(source.lines[max(0, line - 6):line + 2])
            if "try" not in window and "catch" not in window and "NumberFormat" not in window:
                hit("unchecked-parse", source, m.start(), "parsing a number with no catch nearby")


def rule_system_out(sources, index):
    for source in sources:
        for m in re.finditer(r"System\.(out|err)\.print", source.code):
            hit("system-out", source, m.start(), "writes to the JVM's console instead of the log")


def rule_todo(sources, index):
    for source in sources:
        for m in re.finditer(r"\b(TODO|FIXME|XXX|HACK)\b", source.text):
            line = source.lines[source.line_of(m.start()) - 1].strip()
            hit("todo", source, m.start(), line[:120])


def rule_static_mutable(sources, index):
    for source in sources:
        for m in re.finditer(r"static (?!final)(?:volatile )?([A-Za-z_][\w<>\[\], ?.]*) ([a-z]\w*)\s*[=;]",
                             source.code):
            hit("static-mutable", source, m.start(), f"static mutable field {m.group(2)} is shared state")


def rule_unsynchronised_static_map(sources, index):
    for source in sources:
        for m in re.finditer(r"static final (Map|Set|List)<[^;=]*>\s+([A-Z_]\w*)\s*=\s*new (HashMap|HashSet|ArrayList|LinkedHashMap|LinkedHashSet)",
                             source.code):
            hit("shared-collection", source, m.start(),
                f"{m.group(2)} is a plain {m.group(3)} shared by every thread")


def rule_resource_leak(sources, index):
    for source in sources:
        for m in re.finditer(r"new (FileInputStream|FileOutputStream|FileReader|FileWriter)\(", source.code):
            line = source.line_of(m.start())
            window = "\n".join(source.lines[max(0, line - 4):line + 1])
            if "try (" not in window and "try(" not in window:
                hit("resource-leak", source, m.start(), "stream opened outside try-with-resources")


def rule_sleep(sources, index):
    for source in sources:
        for m in re.finditer(r"Thread\.sleep\(|\.wait\(\)|Thread\.yield\(\)", source.code):
            hit("blocking-wait", source, m.start(), "blocking wait on a hot path")


def rule_unsafe_math_random(sources, index):
    for source in sources:
        for m in re.finditer(r"Math\.random\(\)", source.code):
            hit("math-random", source, m.start(), "Math.random() instead of a seeded source")


def rule_catch_null(sources, index):
    # A catch that returns null or a silent default hides a failure the caller
    # cannot see: the engine then reports "nothing happened".
    for source in sources:
        for m in re.finditer(r"catch \(([^)]*)\)\s*\{\s*(?:FaweMod\.LOGGER\.debug\([^;]*;\s*)?return (null|0|false|-1);",
                             source.code):
            hit("swallowed-failure", source, m.start(),
                f"catch ({m.group(1)}) returns {m.group(2)} and loses the reason")


def rule_field_hides_param(sources, index):
    for source in sources:
        for m in re.finditer(r"([A-Za-z_][\w<>\[\]]*) ([a-z]\w*)\)\s*\{", source.code):
            name = m.group(2)
            # a constructor parameter assigned to the same name: `this.x = x` is fine,
            # `x = x` is not
            tail = source.code[m.end():m.end() + 200]
            if re.match(r"\s*" + re.escape(name) + r"\s*=", tail):
                hit("self-assign", source, m.start(), f"parameter {name} is assigned to itself")


RULES = {
    "dead-method": rule_dead_private_method,
    "dead-field": rule_dead_private_field,
    "empty-catch": rule_empty_catch,
    "locale": rule_locale,
    "boxed-identity": rule_boxed_identity,
    "int-overflow": rule_int_overflow,
    "unchecked-parse": rule_parse_without_guard,
    "system-out": rule_system_out,
    "todo": rule_todo,
    "static-mutable": rule_static_mutable,
    "shared-collection": rule_unsynchronised_static_map,
    "resource-leak": rule_resource_leak,
    "blocking-wait": rule_sleep,
    "math-random": rule_unsafe_math_random,
    "swallowed-failure": rule_catch_null,
    "self-assign": rule_field_hides_param,
}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--rule", action="append", default=None)
    parser.add_argument("--path", default=None)
    parser.add_argument("--summary", action="store_true")
    args = parser.parse_args()

    sources = load()
    if args.path:
        sources = [s for s in sources if args.path in s.rel]
    index = "\n".join(s.code for s in sources)
    rules = args.rule or sorted(RULES)
    for name in rules:
        if name not in RULES:
            print(f"unknown rule {name}", file=sys.stderr)
            return 2
        RULES[name](sources, index)

    HITS.sort(key=lambda h: (h[1], h[2]))
    counts: dict[str, int] = {}
    for rule, rel, line, message in HITS:
        counts[rule] = counts.get(rule, 0) + 1
        if not args.summary:
            print(f"{rule:18} {rel}:{line}: {message}")
    print()
    for rule, count in sorted(counts.items(), key=lambda kv: -kv[1]):
        print(f"{count:5}  {rule}")
    print(f"{sum(counts.values()):5}  total")
    return 1 if HITS else 0


if __name__ == "__main__":
    raise SystemExit(main())

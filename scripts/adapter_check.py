#!/usr/bin/env python3
"""Checks the Fabric adapter against the engine it compiles with.

The adapter is only compiled where the Minecraft jars are, so a missing import of
an engine class would first show up in the continuous integration run, minutes
later - which is what happened twice. This walks the adapter for the engine's own
type names and reports the ones used without an import; a name that is written out
in full or only mentioned in a comment is left alone.

It cannot check the Minecraft API the adapter calls: that is what the compile in
the continuous integration run is for.
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
ENGINE = ROOT / "core/src/main/java"
ADAPTER = ROOT / "fabric/src/main/java"

TYPE = re.compile(r"^(?:public |final |abstract |sealed |non-sealed )*(?:class|interface|enum|record) (\w+)",
                  re.M)
MEMBER = re.compile(r"^\s*(?:public |protected |private |static |final |synchronized |native |default |abstract )*"
                    r"[\w<>\[\],.?\s]*?\s(\w+)\s*\(", re.M)


def strip(text):
    """Removes comments and string literals: only code is interesting."""
    text = re.sub(r"/\*.*?\*/", " ", text, flags=re.S)
    text = re.sub(r"//[^\n]*", " ", text)
    text = re.sub(r'"(?:\\.|[^"\\])*"', '""', text)
    return text


def engine_types():
    """Simple name -> file, for every type of the engine."""
    found = {}
    for path in ENGINE.rglob("*.java"):
        for match in TYPE.finditer(path.read_text()):
            found.setdefault(match.group(1), path)
    return found


def engine_members(path):
    """The member names a type of the engine declares, plus what it inherits."""
    names = set()
    for match in MEMBER.finditer(path.read_text()):
        names.add(match.group(1))
    return names


def main():
    types = engine_types()
    problems = []
    for path in sorted(ADAPTER.rglob("*.java")):
        raw = path.read_text()
        code = strip(raw)
        imports = set(re.findall(r"^import [\w.]+\.(\w+);", raw, re.M))
        declared = set(TYPE.findall(raw))
        for name, source in types.items():
            if name in imports or name in declared:
                continue
            # A fully qualified use is fine, and so is a name that is part of a
            # longer word.
            for match in re.finditer(r"\b" + name + r"\b", code):
                before = code[max(0, match.start() - 60):match.start()]
                if before.rstrip().endswith("."):
                    continue
                line = code[:match.start()].count("\n") + 1
                problems.append("%s:%d uses %s without importing it (%s)"
                                % (path.relative_to(ROOT), line, name, source.relative_to(ROOT)))
                break
    for problem in problems:
        print(problem)
    print("adapter imports: %d problems" % len(problems))
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())

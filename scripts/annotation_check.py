#!/usr/bin/env python3
"""Fails when an annotation would not compile under javac.

The sandbox compiles with ECJ, which accepts a couple of things javac refuses -
an `@Override` on a static method is the one that has already slipped through
once. This is a stand-in for the javac error, not a replacement for the build.
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SOURCES = [ROOT / "core/src/main/java", ROOT / "fabric/src/main/java",
           ROOT / "core/src/test/java"]

ANNOTATIONS = ("@Override", "@SafeVarargs", "@FunctionalInterface")


def main():
    problems = []
    for base in SOURCES:
        if not base.exists():
            continue
        for path in base.rglob("*.java"):
            lines = path.read_text().splitlines()
            for index, line in enumerate(lines[:-1]):
                if line.strip() not in ANNOTATIONS:
                    continue
                name = line.strip()
                for following in lines[index + 1:index + 3]:
                    stripped = following.strip()
                    if stripped.startswith("*") or stripped.startswith("/"):
                        continue
                    if name == "@Override" and re.search(r"\bstatic\b", stripped) and "(" in stripped:
                        problems.append("%s:%d @Override on a static method"
                                        % (path.relative_to(ROOT), index + 1))
                    if name == "@FunctionalInterface" and not re.match(
                            r"(public |protected |private |abstract )*interface\b", stripped):
                        problems.append("%s:%d @FunctionalInterface is not on an interface"
                                        % (path.relative_to(ROOT), index + 1))
                    break
    for problem in problems:
        print(problem)
    if problems:
        return 1
    print("annotations: 0 that javac would refuse")
    return 0


if __name__ == "__main__":
    sys.exit(main())

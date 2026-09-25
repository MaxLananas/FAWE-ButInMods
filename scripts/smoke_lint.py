#!/usr/bin/env python3
"""Runs the rcon smoke run's expected answers against the head-less engine.

The smoke run itself needs a live server. This lint dispatches the same lines
through the engine's own dispatcher with a console actor, in a world built like
the flat one the job boots, and reports the rows whose expected text the engine
does not answer - a minute after a push instead of ten minutes into a server
boot. Run it after the engine is compiled:

    python3 scripts/smoke_lint.py [--java java] [--classpath <engine classes>]
"""
import argparse
import ast
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
JAVA = "java"
CLASSPATH = "core/build/classes/java/main:core/build/classes/java/selfTest"
# Rows whose answer depends on the machine the server runs on.
SKIP = {"fawebim path", "//fawebim path"}


def rows():
    tree = ast.parse((REPO / "scripts/rcon_smoke.py").read_text())
    for node in tree.body:
        if isinstance(node, ast.Assign) and getattr(node.targets[0], "id", "") == "CHECKS":
            return [(ast.literal_eval(e.elts[0]), ast.literal_eval(e.elts[1])) for e in node.value.elts]
    raise SystemExit("no CHECKS list in scripts/rcon_smoke.py")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--java", default=JAVA, help="the java binary to run the probe with")
    parser.add_argument("--classpath", default=CLASSPATH)
    parser.add_argument("--work", default="/tmp/fawebim-smoke-lint",
                        help="where the probe is written and run")
    options = parser.parse_args()
    work = Path(options.work)
    work.mkdir(parents=True, exist_ok=True)
    checks = rows()
    commands = work / "commands.txt"
    commands.write_text("\n".join(command for command, _ in checks) + "\n")
    run = subprocess.run([options.java, "-cp", options.classpath, "com.maxlananas.fawebim.core.test.SmokeProbe",
                          str(commands)], capture_output=True, text=True)
    if run.returncode != 0:
        print(run.stdout[-2000:], run.stderr[-2000:])
        return 2
    # A line can appear more than once, so the answers are matched in order.
    answers = [line.partition("\t")[2] for line in run.stdout.splitlines() if "\t" in line]
    if len(answers) != len(checks):
        print(f"the engine answered {len(answers)} of {len(checks)} lines")
        return 2
    bad = 0
    for (command, expected), answer in zip(checks, answers):
        if expected.lower() not in answer.lower():
            print(f"MISMATCH {command}\n    expected: {expected}\n    answer:   {answer}")
            bad += 1
    print(f"smoke rows: {len(checks)}, not answered as expected: {bad}")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())

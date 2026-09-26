#!/usr/bin/env python3
"""Times the mod's big edits on a running server and prints what they cost.

A number the player feels - how long a ``//cut`` of a large selection takes -
cannot be measured without a world: the engine tests run against a double, and
the double has no chunk palettes, no light engine and no client sync. This drives
the same server the smoke test boots, over rcon, and prints the time the mod
itself reports for each edit, alongside the round trip the caller waited for.

It is a check as well as a measurement: every edit has to report the block count
the selection holds, so a build that answers fast because it did nothing fails
here.

The rows run through the engine before the server boots with ``--lint``, the way
the smoke rows do, which is what makes a wrong expectation show up in a minute
rather than ten minutes into a server start:

    python3 scripts/rcon_bench.py --lint --java <java> --classpath <engine classes>
    python3 scripts/rcon_bench.py --wait 60
"""
import argparse
import os
import re
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from rcon_smoke import Rcon, plain  # noqa: E402  (the smoke test's client)

REPO = Path(__file__).resolve().parent.parent
REPORTED = re.compile(r"in ([0-9,]+)ms")


def reported_ms(answer):
    """The time the mod itself reports for an edit, or None when it reports none."""
    match = REPORTED.search(plain(answer))
    return int(match.group(1).replace(",", "")) if match else None


# (label, command, text the answer must hold). The box is 64x32x64 blocks above
# the ground of the flat world the job starts, so it holds air until this run
# fills it: 131,072 blocks is a large edit by the standards of the commands, and
# small enough to run several of them.
STEPS = [
    ("select", "//pos1 -32,32,-32", "position 1: set"),
    ("select", "//pos2 31,63,31", "position 2: set"),
    ("size", "//size", "131,072"),
    # A copy of air stores nothing, however large the selection is.
    ("copy of an empty box", "//copy", "0 block(s)"),
    ("set (fills the box)", "//set minecraft:stone", "131,072 block(s) affected"),
    ("count", "//count minecraft:stone", "count: 131,072"),
    ("copy of a solid box", "//copy", "copied: 131,072 block(s)"),
    ("cut of a solid box", "//cut", "cut: 131,072 block(s)"),
    ("count after the cut", "//count minecraft:stone", "count: 0"),
    ("undo (puts the box back)", "//undo", "undid: 131,072 block change(s)"),
    ("count", "//count minecraft:stone", "count: 131,072"),
    ("undo (empties it again)", "//undo", "undid: 131,072 block change(s)"),
    # The clipboard still holds the box, pasted into the air above it. -a is the
    # switch that skips the clipboard's air, so the paste has nothing to carve.
    ("paste of a large box", "//paste -a 0,96,0", "pasted: 131,072 block(s)"),
    ("select what was pasted", "//pos1 0,96,0", "position 1: set"),
    ("select what was pasted", "//pos2 63,127,63", "position 2: set"),
    ("count what the paste wrote", "//count minecraft:stone", "count: 131,072"),
    ("copy of what was pasted", "//copy", "copied: 131,072 block(s)"),
]


def lint(options):
    """Runs the rows through the engine, the way the smoke rows are checked."""
    work = Path(options.work)
    work.mkdir(parents=True, exist_ok=True)
    commands = work / "bench-commands.txt"
    commands.write_text("\n".join(command for _, command, _ in STEPS) + "\n")
    run = subprocess.run([options.java, "-cp", options.classpath,
                          "com.maxlananas.fawebim.core.test.SmokeProbe", str(commands)],
                         capture_output=True, text=True)
    if run.returncode != 0:
        print(run.stdout[-2000:], run.stderr[-2000:])
        return 2
    answers = [line.partition("\t")[2] for line in run.stdout.splitlines() if "\t" in line]
    if len(answers) != len(STEPS):
        print(f"the engine answered {len(answers)} of {len(STEPS)} lines")
        return 2
    bad = 0
    for (label, command, expected), answer in zip(STEPS, answers):
        if expected.lower() not in plain(answer).lower():
            print(f"MISMATCH {label}: {command}\n    expected: {expected}\n    answer:   {answer}")
            bad += 1
    print(f"bench rows: {len(STEPS)}, not answered as expected: {bad}")
    return 1 if bad else 0


def measure(options):
    deadline = time.time() + options.wait
    while True:
        try:
            client = Rcon(options.host, options.port, options.password)
            break
        except OSError as error:
            if time.time() >= deadline:
                print("cannot reach the rcon port %s:%s (%s)" % (options.host, options.port, error))
                return 1
            time.sleep(2)

    failures = []
    rows = []
    for label, command, expected in STEPS:
        started = time.time()
        try:
            answer = client.run(command)
        except Exception as failure:  # noqa: BLE001  (a broken link is a failed row)
            print("FAIL %-26s %s: %s" % (label, command, failure))
            failures.append("%s: /%s -> %s" % (label, command, failure))
            continue
        trip = (time.time() - started) * 1000
        text = plain(answer)
        ms = reported_ms(answer)
        ok = expected.lower() in text.lower()
        rows.append((label, command, ms, trip, ok))
        print("%s  %-26s /%-28s reported %-8s round trip %6.0fms"
              % ("ok  " if ok else "FAIL", label, command,
                 ("%dms" % ms) if ms is not None else "-", trip))
        if not ok:
            failures.append("%s: /%s -> %s" % (label, command, text.replace("\n", " / ")[:200]))

    print()
    print("%-26s %10s %12s" % ("step", "mod reports", "round trip"))
    for label, command, ms, trip, ok in rows:
        print("%-26s %10s %10.0fms" % (label, ("%dms" % ms) if ms is not None else "-", trip))

    if failures:
        print()
        for failure in failures:
            print("FAIL", failure)
        return 1
    print()
    print("bench: %d steps, 0 failures" % len(STEPS))
    return 0


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=25575)
    parser.add_argument("--password", default="fawebim")
    parser.add_argument("--wait", type=float, default=30.0,
                        help="seconds to keep trying to reach the server")
    parser.add_argument("--lint", action="store_true",
                        help="run the rows through the engine instead of a server")
    parser.add_argument("--java", default="java", help="the java binary to run the lint with")
    parser.add_argument("--classpath", default="core/build/classes/java/main:core/build/classes/java/selfTest")
    parser.add_argument("--work", default="/tmp/fawebim-bench-lint")
    args = parser.parse_args()
    return lint(args) if args.lint else measure(args)


if __name__ == "__main__":
    sys.exit(main())

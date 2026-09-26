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
"""
import argparse
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from rcon_smoke import Rcon, plain  # noqa: E402  (the smoke test's client)

REPORTED = re.compile(r"in ([0-9,]+)ms")


def reported_ms(answer):
    """The time the mod itself reports for an edit, or None when it reports none."""
    match = REPORTED.search(plain(answer))
    return int(match.group(1).replace(",", "")) if match else None


# (label, command, text the answer must hold). The box is 64x32x64 = 131,072
# blocks at y=-64..-33, which the flat world the job starts fills with four
# layers of ground and air above: the same box measures a mostly empty region and
# then, once it is filled with stone, a solid one.
STEPS = [
    ("select", "//pos1 -32,-64,-32", "position 1: set"),
    ("select", "//pos2 31,-33,31", "position 2: set"),
    ("size", "//size", "131,072"),
    ("copy of an empty box", "//copy", "copied: 131,072 block(s)"),
    ("set (fills the box)", "//set minecraft:stone", "131,072 block(s) affected"),
    ("count", "//count minecraft:stone", "count: 131,072"),
    ("copy of a solid box", "//copy", "copied: 131,072 block(s)"),
    ("cut of a solid box", "//cut", "cut: 131,072 block(s)"),
    ("count after the cut", "//count minecraft:stone", "count: 0"),
    ("undo (puts the box back)", "//undo", "undid"),
    ("count", "//count minecraft:stone", "count: 131,072"),
    ("undo (empties it again)", "//undo", "undid"),
]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=25575)
    parser.add_argument("--password", default="fawebim")
    parser.add_argument("--wait", type=float, default=30.0,
                        help="seconds to keep trying to reach the server")
    args = parser.parse_args()

    deadline = time.time() + args.wait
    while True:
        try:
            client = Rcon(args.host, args.port, args.password)
            break
        except OSError as error:
            if time.time() >= deadline:
                print("cannot reach the rcon port %s:%s (%s)" % (args.host, args.port, error))
                return 1
            time.sleep(2)

    failures = []
    rows = []
    for label, command, expected in STEPS:
        started = time.time()
        answer = client.run(command)
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
        if ms is None and trip < 50:
            continue
        print("%-26s %10s %10.0fms" % (label, ("%dms" % ms) if ms is not None else "-", trip))

    if failures:
        print()
        for failure in failures:
            print("FAIL", failure)
        return 1
    print()
    print("bench: %d steps, 0 failures" % len(STEPS))
    return 0


if __name__ == "__main__":
    sys.exit(main())

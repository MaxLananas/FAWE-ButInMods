#!/usr/bin/env python3
"""Checks every command is reachable under the spelling upstream uses.

WorldEdit types a command by prefixing the name it declares with one slash, so a
name declared as ``/set`` is typed ``//set`` and one declared as ``tool`` is typed
``/tool``. The platform registers what the entry's registration name says, so an
entry registered as ``set`` when upstream declares ``/set`` is a command a player
cannot reach the way the documentation says.

This audit joins the upstream inventory with the registered spec and reports the
names whose spelling differs.

Usage:
    python3 scripts/spelling_audit.py [--inventory docs/commands-inventory.json]
                                      [--spec docs/commands-spec.json]
"""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

INVENTORY = "docs/commands-inventory.json"
SPEC = "docs/commands-spec.json"


def bare(name: str) -> str:
    return re.sub(r"^/+", "", name)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--inventory", default=INVENTORY)
    parser.add_argument("--spec", default=SPEC)
    args = parser.parse_args()

    inventory = json.loads(Path(args.inventory).read_text())
    spec = json.loads(Path(args.spec).read_text())["commands"]

    registered: dict[str, str] = {}
    for command in spec:
        typed = "/" + command["registration"]
        for spelling in [command["name"], *command["aliases"]]:
            registered.setdefault(bare(spelling), typed)

    mismatched = []
    unseen = []
    for item in inventory:
        # Only the primary name says how the command is typed; an alias is a
        # second spelling a platform may add on its own.
        for spelling in [item["name"]]:
            name = bare(spelling)
            if not name or name in ("*",) or name.startswith("-"):
                continue
            expected = "/" + spelling if spelling.startswith("/") else "/" + name
            found = registered.get(name)
            if found is None:
                unseen.append(name)
            elif found != expected:
                mismatched.append((name, spelling, expected, found))

    seen = set()
    for name, spelling, expected, found in mismatched:
        if name in seen:
            continue
        seen.add(name)
        print(f"declared {spelling!r:34s} typed {expected:22s} registered as {found}")
    print()
    print(f"names upstream declares: {len(registered)} matched, "
          f"{len(set(unseen))} not registered, {len(seen)} typed differently")
    for name in sorted(set(unseen)):
        print(f"  not registered: {name}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

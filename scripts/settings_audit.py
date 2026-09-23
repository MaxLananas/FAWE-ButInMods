#!/usr/bin/env python3
"""Reports configuration keys that nothing in the mod reads.

A setting is worth shipping only when it changes something. The declaration
table of {@code Config} says which field each key writes, and this audit looks
for that field in the rest of the sources: a key whose field no other file
mentions is a knob that pretends to do something.

Usage:
    python3 scripts/settings_audit.py [--config <path to Config.java>] [--roots core/src/main/java fabric/src/main/java]
"""

from __future__ import annotations

import argparse
import pathlib
import re
import sys

DEFAULT_CONFIG = "core/src/main/java/com/maxlananas/fawebim/core/platform/Config.java"

# The declaration helpers of Config: kind, key, path, fallback, description,
# reader, writer. The reader lambda names the field the key writes.
DECLARATION = re.compile(
    r'\b(?:bool|integer|text)\(\s*"([^"]+)",\s*"([^"]+)"[\s\S]*?\(\)\s*->\s*(\w+)\s*,')


def declared(config: str) -> list[tuple[str, str, str]]:
    """Every (key, path, field) the configuration declares."""
    return [(key, path, field) for key, path, field in DECLARATION.findall(config)]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", default=DEFAULT_CONFIG)
    parser.add_argument("--roots", nargs="*",
                        default=["core/src/main/java", "fabric/src/main/java"])
    options = parser.parse_args()

    config_path = pathlib.Path(options.config)
    config = config_path.read_text()
    entries = declared(config)
    sources = []
    for root in options.roots:
        sources.extend(path for path in pathlib.Path(root).rglob("*.java") if path != config_path)

    unread = []
    for key, path, field in entries:
        pattern = re.compile(r"\b" + re.escape(field) + r"\b")
        readers = [source for source in sources if pattern.search(source.read_text())]
        if not readers:
            unread.append((key, path, field))

    print(f"settings declared: {len(entries)}, read nowhere else: {len(unread)}")
    for key, path, field in unread:
        print(f"  {key} ({path}) -> field '{field}'")
    sys.exit(1 if unread else 0)


if __name__ == "__main__":
    main()

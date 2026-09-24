#!/usr/bin/env python3
"""Print the platform API a Fabric build compiles against.

Called by the temporary diagnose workflow. The Gradle job has the mapped game
on its classpath, so javap answers what a class looks like in the exact version
being built, and the answer is printed as check annotations because the job's
files cannot be downloaded from here. Delete this script with the workflow.
"""
import os
import re
import subprocess
import sys

CACHE = os.path.expanduser("~/.gradle/caches")

# class -> what to keep, or None for the whole listing
CLASSES = {
    "net.minecraft.client.input.KeyEvent": None,
    "com.mojang.authlib.GameProfile": None,
    "net.minecraft.client.gui.screens.Screen":
        re.compile(r"keyPressed|keyReleased|charTyped|mouseClicked|isPauseScreen|onClose|render\("),
    "net.minecraft.server.level.ServerLevel": re.compile(r"sendParticles"),
    "net.minecraft.server.level.ServerPlayer": re.compile(r"getServer|\bserver\b|level\(\)"),
    "net.minecraft.world.level.chunk.LevelChunk": re.compile(r"markUnsaved|setUnsaved|getSection"),
    "net.minecraft.world.entity.player.Inventory": re.compile(r"SelectedSlot|selected"),
    "net.minecraft.client.gui.components.EditBox": re.compile(r"keyPressed|Value|isFocused"),
    "net.minecraft.client.gui.components.AbstractWidget": re.compile(r"keyPressed|mouseClicked"),
}

LIMIT = 3200
CHUNKS = 6


def jars():
    found = []
    for root, _, files in os.walk(CACHE):
        for name in files:
            if name.endswith(".jar") and "-sources" not in name and "-javadoc" not in name:
                found.append(os.path.join(root, name))
    return found


def report(lines):
    text = "\n".join(lines)
    print("::error title=probe summary::%d characters" % len(text))
    chunks = [text[i:i + LIMIT] for i in range(0, len(text), LIMIT)] or [""]
    if len(chunks) > CHUNKS:
        chunks = chunks[:CHUNKS] + ["... the report was cut here, it is in probe.txt"]
    for index, chunk in enumerate(chunks, start=1):
        message = chunk.replace("%", "%25").replace("\r", "").replace("\n", "%0A")
        print("::error title=probe %d/%d::%s" % (index, len(chunks), message))


def main():
    found = jars()
    out = ["# jars in the Gradle cache: %d" % len(found)]
    classpath = os.pathsep.join(found)
    for name, pattern in CLASSES.items():
        out.append("")
        result = subprocess.run(
            ["javap", "-p", "-cp", classpath, name], capture_output=True, text=True
        )
        text = result.stdout.strip() or result.stderr.strip() or "missing: " + name
        if pattern is not None and result.stdout.strip():
            kept = [line for line in text.splitlines() if pattern.search(line)]
            text = "\n".join(kept) if kept else "no matching member"
        out.append(text)
    with open("probe.txt", "w") as handle:
        handle.write("\n".join(out) + "\n")
    report(out)
    print("wrote probe.txt", file=sys.stderr)


if __name__ == "__main__":
    main()

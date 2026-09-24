#!/usr/bin/env python3
"""Print the platform API a Fabric build compiles against.

Called by the temporary diagnose workflow: it lists the game jars Loom has
cached, then runs javap over the symbols the adapter uses, so the API of the
exact Minecraft version in gradle.properties can be read without a local
toolchain. Delete this script with the workflow it serves.
"""
import os
import subprocess
import sys

CACHE = os.path.expanduser("~/.gradle/caches")
CLASSES = [
    "net.minecraft.world.level.chunk.LevelChunkSection",
    "net.minecraft.world.level.chunk.PalettedContainer",
    "net.minecraft.world.level.chunk.PalettedContainerRO",
    "net.minecraft.world.level.chunk.ChunkAccess",
    "net.minecraft.world.level.chunk.LevelChunk",
    "net.minecraft.world.level.chunk.status.ChunkStatus",
    "net.minecraft.world.level.chunk.ChunkGenerator",
    "net.minecraft.world.level.biome.BiomeResolver",
    "net.minecraft.world.level.biome.Climate$Sampler",
    "net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket",
    "net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket",
    "net.minecraft.server.network.ServerGamePacketListenerImpl",
    "net.minecraft.server.level.ServerChunkCache$MainThreadExecutor",
]


def jars():
    found = []
    for root, _, files in os.walk(CACHE):
        for name in files:
            if not name.endswith(".jar"):
                continue
            if "-sources" in name or "-javadoc" in name:
                continue
            found.append(os.path.join(root, name))
    return found


def main():
    candidates = jars()
    out = ["# game jars in the cache"]
    out.extend(sorted(candidates))
    game = [p for p in candidates if "minecraft" in os.path.basename(p).lower()]
    game.sort(key=len)
    if not game:
        with open("probe.txt", "w") as handle:
            handle.write("\n".join(out) + "\nno minecraft jar found\n")
        return
    jar = game[0]
    out.append("")
    out.append("# javap against " + jar)
    for name in CLASSES:
        out.append("")
        result = subprocess.run(
            ["javap", "-p", "-cp", jar, name], capture_output=True, text=True
        )
        out.append(result.stdout.strip() or result.stderr.strip() or "missing: " + name)
    with open("probe.txt", "w") as handle:
        handle.write("\n".join(out) + "\n")
    print("wrote probe.txt for", jar, file=sys.stderr)


if __name__ == "__main__":
    main()

# Changelog

Every release of FAWE-BIM, newest first. The defect ledger behind these entries — cause, fix and file for
each of the **33** defects found on this line — is [`BUGS.md`](BUGS.md).

---

## 1.2.1 — Minecraft 1.21.10

The mod for **Minecraft 1.21.10**: Java 21, Fabric Loader 0.17.3, Fabric API 0.136.0+1.21.10, Loom
1.11.8, against the official Mojang mappings with the parchment layer. It is not a recompile of the
26.2 build: the engine is the same one, the adapter is written for the 1.21.10 game.

### 33 defects found and fixed

The audit of the 26.2 line was replayed on this port, and the CI of this branch boots the game with the
mod in it and drives **512 commands** over rcon — every command the mod registers, its combinations, its
refusals and the counts that must be exactly right. It reads the log of the server while it runs: a
command that answers politely while the engine logs the failure it did not expect is a defect, and the
sweep names the command that caused it. The ones a builder would have felt:

- **An unknown block name was worth air.** `BuiltInRegistries.BLOCK.getValue` answers the default value
  of the registry for a key it does not hold — air for blocks — and the check for null was never true:
  `//set notablock` filled the selection with air, and `//replace stone notablock` erased the stone
  instead of refusing the name. The same was true of the features, the placed features, the structures
  and the items: `//feature pasunefeature` placed the default feature, and an item that does not exist
  gave the player an empty stack.
- **`//setbiome` wrote three quarters of its biomes in the wrong cell** (`x & 3` instead of
  `(x & 15) >> 2`), and the biome flush read the biomes of the bottom of the world for every section:
  the section index was used where the height belongs, so `//setbiome` wrote the biome of y=0..12 into
  the twenty-four sections above it.
- **`//setbiome` walked its cells from the lowest block of the selection.** A biome cell covers 4×4×4
  blocks, so a selection from y=70 to y=80 holds the cells 68, 72, 76 and 80, and the walk from 70
  reached 68, 72 and 76: the top of every column kept its biome.
- **The chunk cache could name a chunk the level had thrown away.** A chunk that is unloaded and loaded
  again is a **new** object: the writes of a long edit went into the old one, and therefore nowhere. The
  cache is tied to the tick now.
- **The biome ids were only read at start-up.** `/reload` — or a datapack added on the fly — replaces
  the registry access of the server, and a biome added in the middle renumbers all the ones after it,
  because the ids are the sorted names: `//setbiome` named the wrong biome for half the alphabet, and
  the ids written into a schematic or a history file pointed at their neighbours.
- **The super pickaxe dropped the contents of a chest the player still had**, and ignored the tool in
  hand, so fortune and silk touch did nothing: a super pickaxe on diamond ore gave a diamond, whatever
  the pickaxe was.
- **The scroll binding restored the held slot before the packet had moved it.** The injection was at the
  `HEAD` of `handleSetCarriedItem`, and the vanilla body sets the slot *after* it: the restore was
  undone two lines later, so a scroll on a bound tool moved the hotbar **and** fired the binding.
- **The reach was a fixed pair of numbers**, so a datapack that moved the range of the block
  interaction was ignored. The game answers now.
- **A generator emptied a chest that was already there**: the block was written after its block entity,
  so the chest was emptied when the level closed.
- **`//count` refused to run without a mask**, though the mask is optional in WorldEdit and in FAWE.
- **`//gmask clear` installed a mask that matched nothing** instead of clearing the global mask, and
  `//smask clear` did the same for the brush mask.
- **The wait for a regeneration was not bounded.** The temporary level drained the queue of the server's
  tasks in a loop without a limit: a task that waited for ever froze the server in the middle of a
  `//regen`. The wait is bounded to thirty seconds now, and a queue that is still busy is reported.
- **An entity of an unknown type emptied the copy with an exception.** The type has no registry id to
  write, so its data could not be read back into a world: the key was dereferenced without a check. The
  entity is left out of the copy.
- **A session whose world was gone threw a `NullPointerException`.** The world of a session is held
  weakly, and the adapter makes one per click: a mask or a brush (`#angle`, `/tool`) can live longer than
  the world it was parsed for.
- **The pattern and mask parsers swallowed every runtime failure**, so a bug in one of them looked like
  a syntax error, and the message of a failure without a text said "null", which is worse than naming
  the kind of error.
- **`#angle` and `#surfaceangle` dropped the sign of a negative limit.** The parser took every `-` out
  of the string: `#angle[-45d][45d]` selected exactly what `#angle[45d][45d]` selected. A `-` after the
  first character separates two numbers; a `-` at the front is a sign.
- **The super pickaxe dropped its loot in a `finally`**: the block ran even when the flush had failed,
  so the drops of a chest the player still had could appear. The loop keeps the failure, closes the
  edit, drops only the blocks the world really lost, and throws the failure again.
- **The history folder was never pruned**, and the history and the snapshot files were sorted as text,
  where "f" comes after "10": the sixteenth file of a millisecond was read as the oldest.
- **The NBT reader never closed the inflater it opened** (native memory leaked), **the varint reader
  read a sixth byte and dropped its bits**, and **a schematic in the MCEdit format allocated two arrays
  of the size of the volume**: a big clipboard ended in an `OutOfMemoryError` instead of an answer.
- **`//schem save house.schem mcedit` wrote `house.schem.schematic`**, a file nothing else opens, and a
  schematic whose extension was not in lower case was invisible to the listing.

### Optimisations

- The states a flush replaced are kept between flushes: a big edit flushed a chunk after the other and
  allocated two arrays of 98k references every time, one and a half megabytes of garbage per chunk.
- The array of the light cells is allocated on the first cell, not for every adapter: one is built per
  click and per selection tick, and each one carried eight kilobytes whether it flushed a chunk or not.
- The parameter types of a method of the level are read once per class: `getParameterTypes` answers a
  fresh clone, and a tree or a structure calls it thousands of times per generation.
- The particles of the selection are drawn when it changed: a player who stood still saw the same three
  hundred packets go out again and again, and the signature of a player who left is forgotten.

### The CI

The workflow of the 26.2 line, adapted to 1.21.10 and much faster: one job, one Gradle invocation, one
JDK, one checkout, a warm cache, and no artifact between the build and the server that runs the mod.

- **512 commands** driven over rcon against the running game: every command the mod registers, its
  combinations, its refusals, and the counts that must be exactly right (a 16×16×16 selection holds
  4096 blocks). The sweep reads the log of the server while it runs and names the command whose worker
  failed.
- The log is read by `tools/logcheck.py`, which asks for the line the mod writes when it is ready and
  fails on the first stack trace it holds. The findings are short and named, because the output of a
  step that fails is not readable from the run page.
- The engine's own bench runs in the same Gradle invocation (`build :core:verify`): the **35** test
  classes of the engine, among them the audit tests that cover the parsers of the patterns and the
  masks, the cells of the biomes and the history.
- The static audit (`tools/audit.py`) reads the sources and prints the defect classes that have bitten
  this project; it runs in about eighteen seconds.
- **Under three minutes** for the whole run on a warm cache: the build takes seconds, the server is up
  in sixteen, and the sweep answers every command.

### Install

Drop the jar in `mods/`, with Fabric API `0.136.0+1.21.10` and Fabric Loader `0.17.3` or newer, on
Java 21.

---

#!/usr/bin/env python3
"""Smoke-check the built member module (target/horizon-mod/member/.../fawebim.wasm).

Verifies, without a Minecraft server:

  1. the module compiles under wasmtime;
  2. its imports and exports match the Horizon wasm group ABI
     (dylink.0 `env`/`GOT.mem` imports, `horizon::host`/`host_reply`,
     `horizon_layout`/`horizon_in`/`horizon_hook` exports);
  3. the module instantiates against a minimal linker harness, applies its
     data relocations and returns a stable `horizon_layout()` hash.

Usage:  python3 tools/check-wasm.py [path/to/fawebim.wasm]

Requires the `wasmtime` Python package (`pip install wasmtime`; tested with
50.0.1). Exits non-zero on any failure. The host functions are dummies
(`host` returns 0), so this checks ABI shape and static initialization, not
game behavior.
"""
import sys

try:
    import wasmtime
except ImportError:
    sys.exit('wasmtime Python package not installed (pip install wasmtime)')

PATH = sys.argv[1] if len(sys.argv) > 1 else (
    'target/horizon-mod/member/wasm32-unknown-unknown/release/fawebim.wasm')

EXPECTED_IMPORTS = {('env', 'memory'), ('env', '__indirect_function_table'),
                     ('env', '__stack_pointer'), ('env', '__memory_base'),
                     ('env', '__table_base'), ('horizon', 'host'),
                     ('horizon', 'host_reply'), ('GOT.mem', '__heap_base'),
                     ('GOT.mem', '__heap_end'), ('GOT.mem', 'horizon_group_state')}
EXPECTED_EXPORTS = {'horizon_layout', 'horizon_in', 'horizon_hook',
                    '__horizon_mod_hooks', '__wasm_apply_data_relocs'}

data = open(PATH, 'rb').read()
engine = wasmtime.Engine()
module = wasmtime.Module(engine, data)

imports = {(i.module, i.name) for i in module.imports}
exports = {e.name for e in module.exports}
missing_imports = EXPECTED_IMPORTS - imports
missing_exports = EXPECTED_EXPORTS - exports
if missing_imports:
    sys.exit(f'missing imports: {sorted(missing_imports)}')
if missing_exports:
    sys.exit(f'missing exports: {sorted(missing_exports)}')
print(f'{PATH}: ABI imports/exports OK ({len(data)} bytes)')

store = wasmtime.Store(engine)
linker = wasmtime.Linker(engine)
memtype = [i.type for i in module.imports if (i.module, i.name) == ('env', 'memory')][0]
tabletype = [i.type for i in module.imports if (i.module, i.name) == ('env', '__indirect_function_table')][0]
# The import only fixes a lower bound on the memory, so create a comfortable one:
# the static image is ~190 KB and heap growth starts at the imported minimum
# (see GOT.mem below); the stack must start ABOVE the image instead of at its
# midpoint, or deep frames quietly stomp static .rodata (this made the
# float→string path read corrupted digit tables).
PAGES = 32
memory = wasmtime.Memory(store, wasmtime.MemoryType(wasmtime.Limits(PAGES, None)))
table = wasmtime.Table(store, tabletype, wasmtime.Val.ref_null(wasmtime.ValType.funcref()))
linker.define(store, 'env', 'memory', memory)
linker.define(store, 'env', '__indirect_function_table', table)

i32 = wasmtime.ValType.i32()
def global_i32(mutable, init=0):
    return wasmtime.Global(store, wasmtime.GlobalType(i32, mutable), wasmtime.Val.i32(init))

linker.define(store, 'env', '__stack_pointer', global_i32(True, PAGES * 65536))
linker.define(store, 'env', '__memory_base', global_i32(False, 0))
linker.define(store, 'env', '__table_base', global_i32(False, 0))
linker.define(store, 'GOT.mem', '__heap_base', global_i32(True, memtype.limits.min * 65536))
linker.define(store, 'GOT.mem', '__heap_end', global_i32(True, memtype.limits.min * 65536))
linker.define(store, 'GOT.mem', 'horizon_group_state', global_i32(True, 0))

# The guest reaches the game through one import: `host(id, in_ptr, in_len, out_ptr, out_cap)`.
# Every call the mod may make from its hooks so far is answered with the canned success the game
# would give; anything else raises, which surfaces as a trap naming the import id, instead of a
# guest-side `.expect()` panic at a wrong-typed (empty) answer.
HOST_BLOCK_STATE_ID = 4        # BlockStatesHost::block_state_id -> Option<BlockState>
HOST_BLOCK_STATE_STRING = 5    # BlockStatesHost::block_state_string -> Option<String>
HOST_LEVEL = 24                # EntitiesHost::level -> Option<LevelRef>
HOST_EDIT_BLOCKS = 132         # WorldEditServerHost::edit_blocks -> Result<u64, String>
HOST_DIMENSION_KEY_ID = 454    # LevelsBackend::dimension_key_id -> Option<String>
HOST_LEVEL_BLOCK_STATE = 467   # LevelsBackend::level_block_state -> Option<BlockState>
HOST_LEVEL_HEIGHT = 474        # LevelsBackend::level_height -> Option<(i32, i32)>
HOST_COMMANDS_FEEDBACK = 1133  # Commands::feedback — answered with Result<(), TextFormError>::Ok(())
feedback_calls = []
edit_calls = []

MOCK_BLOCKS = {
    'air': (0, 'minecraft:air'),
    'minecraft:air': (0, 'minecraft:air'),
    'stone': (1, 'minecraft:stone'),
    'minecraft:stone': (1, 'minecraft:stone'),
    'minecraft:orange_wool': (2, 'minecraft:orange_wool'),
}
MOCK_NAMES_BY_KEY = {0: b'minecraft:air', 1: b'minecraft:stone', 2: b'minecraft:orange_wool'}

def write_answer(answer: bytes, out_ptr: int, out_cap: int) -> int:
    if len(answer) > out_cap:
        return len(answer)  # guest calls host_reply to fetch the rest
    memory.write(store, bytearray(answer), out_ptr)
    return len(answer)

def host_handler(id_, in_ptr, in_len, out_ptr, out_cap):
    args = bytes(memory.read(store, in_ptr, in_ptr + in_len))
    if id_ == HOST_COMMANDS_FEEDBACK:
        feedback_calls.append(args)
        return write_answer(b'\x00', out_ptr, out_cap)
    if id_ == HOST_BLOCK_STATE_ID:
        length = int.from_bytes(args[:4], 'little')
        name = args[4:4 + length].decode('utf-8')
        if name in MOCK_BLOCKS:
            bkey, _ = MOCK_BLOCKS[name]
            return write_answer(b'\x01' + bkey.to_bytes(4, 'little') + (0).to_bytes(2, 'little'), out_ptr, out_cap)
        return write_answer(b'\x00', out_ptr, out_cap)
    if id_ == HOST_BLOCK_STATE_STRING:
        bkey = int.from_bytes(args[:4], 'little')
        if bkey in MOCK_NAMES_BY_KEY:
            s = MOCK_NAMES_BY_KEY[bkey]
            return write_answer(b'\x01' + len(s).to_bytes(4, 'little') + s, out_ptr, out_cap)
        return write_answer(b'\x00', out_ptr, out_cap)
    if id_ == HOST_LEVEL:
        # Option::Some(LevelRef { epoch: WorldEpoch(1), dimension: DimensionKey(0), generation: 0 })
        return write_answer(b'\x01' + (1).to_bytes(8, 'little') + (0).to_bytes(4, 'little') + (0).to_bytes(4, 'little'), out_ptr, out_cap)
    if id_ == HOST_LEVEL_HEIGHT:
        return write_answer(b'\x01' + (-64).to_bytes(4, 'little', signed=True) + (384).to_bytes(4, 'little', signed=True), out_ptr, out_cap)
    if id_ == HOST_LEVEL_BLOCK_STATE:
        # Option::Some(minecraft:air)
        return write_answer(b'\x01' + (0).to_bytes(4, 'little') + (0).to_bytes(2, 'little'), out_ptr, out_cap)
    if id_ == HOST_DIMENSION_KEY_ID:
        dim = b'minecraft:overworld'
        return write_answer(b'\x01' + len(dim).to_bytes(4, 'little') + dim, out_ptr, out_cap)
    if id_ == HOST_EDIT_BLOCKS:
        edit_calls.append(args)
        # Result::Ok(80u64)
        return write_answer(b'\x00' + (80).to_bytes(8, 'little'), out_ptr, out_cap)
    raise RuntimeError(f'unexpected host import id {id_} with args {args.hex()}')

host_type = wasmtime.FuncType([i32] * 5, [i32])
linker.define(store, 'horizon', 'host', wasmtime.Func(store, host_type, host_handler))
reply_type = wasmtime.FuncType([i32], [])
linker.define(store, 'horizon', 'host_reply', wasmtime.Func(store, reply_type, lambda *a: None))

instance = linker.instantiate(store, module)
out = instance.exports(store)
out.get('__wasm_apply_data_relocs')(store)
layout_a = out.get('horizon_layout')(store)
layout_b = out.get('horizon_layout')(store)
assert layout_a == layout_b, 'horizon_layout() is not deterministic'
print(f'instantiated OK; horizon_layout() = 0x{layout_a & 0xFFFFFFFFFFFFFFFF:016x} (stable)')

# ---------------------------------------------------------------------------
# Functional smoke check: drive the guest through its real entry points the
# way the game does — `horizon_in` to hand it a request, `horizon_hook(id)`,
# then read the reply from its memory. This checks ABI *behavior* (the mod
# really registers its commands and answers a command), not just the shape.
#
# The call ids below are the guest export positions generated from the SDK
# pinned in tools/fetch-sdk.sh; `register_commands` is 20, `run_mod_command`
# is 21 and the synthetic `Hooks::subscribed` call is 0. If the SDK layout
# changes, the decodes below fail loudly rather than asserting the wrong
# thing — find the new ids in the generated `wire_guest_generated.rs`.
# ---------------------------------------------------------------------------
HOOK_SUBSCRIBED = 0
HOOK_REGISTER_COMMANDS = 20
HOOK_RUN_MOD_COMMAND = 21


def call_hook(store, memory, exports, hook_id, request: bytes) -> bytes:
    in_ptr = exports.get('horizon_in')(store, len(request))
    assert in_ptr is not None
    memory.write(store, bytearray(request), in_ptr)
    reply = exports.get('horizon_hook')(store, hook_id)
    pointer, length = reply >> 32, reply & 0xFFFFFFFF
    if length == 0xFFFFFFFF:
        raw = bytes(memory.read(store, pointer, pointer + 4096))
        size = int.from_bytes(raw[:4], 'little')
        raise SystemExit(f'hook {hook_id} refused the call: {raw[4:4 + size]!r}')
    return bytes(memory.read(store, pointer, pointer + length))


def take_u32(buf, off):
    return int.from_bytes(buf[off:off + 4], 'little'), off + 4


def take_f64(buf, off):
    import struct
    return struct.unpack_from('<d', buf, off)[0], off + 8


def take_string(buf, off):
    length, off = take_u32(buf, off)
    return buf[off:off + length].decode('utf-8'), off + length


def take_vec(buf, off, item):
    count, off = take_u32(buf, off)
    items = []
    for _ in range(count):
        value, off = item(buf, off)
        items.append(value)
    return items, off


def take_command(buf, off):
    usage, off = take_string(buf, off)
    return {'usage': usage, 'level': buf[off]}, off + 1


def take_subscription(buf, off):
    hook, off = take_string(buf, off)
    priority, off = take_u32(buf, off)
    # i32 priority, stored little-endian two's complement.
    priority = priority - (1 << 32) if priority >= (1 << 31) else priority
    receive_cancelled = bool(buf[off])
    return (hook, priority, receive_cancelled), off + 1


# `memory` from above is the `env.memory` we linked in; the guest shares it.
exports = instance.exports(store)

# 1) The mod's subscriptions, by name.
reply = call_hook(store, memory, exports, HOOK_SUBSCRIBED, b'')
subscriptions, off = take_vec(reply, 0, take_subscription)
assert off == len(reply), f'trailing bytes in the subscriptions reply ({off}/{len(reply)})'
subscribed = {name for name, _, _ in subscriptions}
print(f'subscriptions: {subscriptions}')
assert subscribed == {'server_started', 'register_commands', 'run_mod_command'}, \
    f'unexpected subscriptions: {sorted(subscribed)}'

# 2) The command tree the mod offers: two usages of /fawebim calc, both
# operator level 2.
reply = call_hook(store, memory, exports, HOOK_REGISTER_COMMANDS, (0).to_bytes(4, 'little'))
commands, off = take_vec(reply, 0, take_command)
assert off == len(reply), f'trailing bytes in the commands reply ({off}/{len(reply)})'
print(f'commands: {commands}')
usages = {(c['usage'], c['level']) for c in commands}
assert ('fawebim calc <formula:text>', 2) in usages
assert ('fawebim calc at <x:number> <y:number> <z:number> <formula:text>', 2) in usages
assert ('fawebim pos1 <x:integer> <y:integer> <z:integer>', 2) in usages
assert ('fawebim pos2 <x:integer> <y:integer> <z:integer>', 2) in usages
assert ('fawebim sel [mode:text]', 2) in usages
assert ('fawebim set <block:text>', 2) in usages
assert ('fawebim replace <from:text> <to:text>', 2) in usages
assert ('fawebim copy', 2) in usages
assert ('fawebim cut [pattern:text]', 2) in usages
assert ('fawebim paste', 2) in usages
assert ('fawebim rotate <degrees:integer>', 2) in usages
assert ('fawebim flip <axis:text>', 2) in usages
assert ('fawebim clearclipboard', 2) in usages
assert ('fawebim schemsave <name:text>', 2) in usages
assert ('fawebim schemload <name:text>', 2) in usages
assert ('fawebim schemlist', 2) in usages
assert ('fawebim sphere <x:integer> <y:integer> <z:integer> <radius:number> <pattern:text>', 2) in usages
assert ('fawebim hsphere <x:integer> <y:integer> <z:integer> <radius:number> <pattern:text>', 2) in usages
assert ('fawebim cylinder <x:integer> <y:integer> <z:integer> <radius:number> <height:integer> <pattern:text>', 2) in usages
assert ('fawebim hcylinder <x:integer> <y:integer> <z:integer> <radius:number> <height:integer> <pattern:text>', 2) in usages
assert ('fawebim pyramid <x:integer> <y:integer> <z:integer> <size:integer> <pattern:text>', 2) in usages
assert ('fawebim hpyramid <x:integer> <y:integer> <z:integer> <size:integer> <pattern:text>', 2) in usages
assert ('fawebim line <x1:integer> <y1:integer> <z1:integer> <x2:integer> <y2:integer> <z2:integer> <pattern:text>', 2) in usages
assert ('fawebim cone <x:integer> <y:integer> <z:integer> <radius:number> <height:integer> <pattern:text>', 2) in usages
assert ('fawebim hcone <x:integer> <y:integer> <z:integer> <radius:number> <height:integer> <pattern:text>', 2) in usages
assert ('fawebim torus <x:integer> <y:integer> <z:integer> <major:number> <minor:number> <pattern:text>', 2) in usages
assert ('fawebim htorus <x:integer> <y:integer> <z:integer> <major:number> <minor:number> <pattern:text>', 2) in usages
assert ('fawebim layers <layers:text>', 2) in usages
assert ('fawebim scatter <x:integer> <y:integer> <z:integer> <radius:integer> <points:integer> <pattern:text>', 2) in usages
assert ('fawebim walls <pattern:text>', 2) in usages
assert ('fawebim faces <pattern:text>', 2) in usages
assert ('fawebim overlay <pattern:text>', 2) in usages
assert ('fawebim hollow [thickness:integer] <pattern:text>', 2) in usages
assert ('fawebim move <dx:integer> <dy:integer> <dz:integer>', 2) in usages
assert ('fawebim stack <count:integer> <dx:integer> <dy:integer> <dz:integer>', 2) in usages
assert ('fawebim smooth [iterations:integer]', 2) in usages
assert ('fawebim erode [faces:integer] [iterations:integer]', 2) in usages
assert ('fawebim dilate [faces:integer] [iterations:integer]', 2) in usages
assert ('fawebim brush <kind:text>', 2) in usages
assert ('fawebim brushset <radius:number> <pattern:text>', 2) in usages
assert ('fawebim brushuse <x:integer> <y:integer> <z:integer>', 2) in usages
assert ('fawebim brushlist', 2) in usages
assert ('fawebim session', 2) in usages
assert ('fawebim limit <blocks:integer>', 2) in usages
assert ('fawebim timeout <seconds:integer>', 2) in usages
assert ('fawebim fast <enabled:text>', 2) in usages
assert ('fawebim tips <enabled:text>', 2) in usages
assert ('fawebim watchdog <enabled:text>', 2) in usages
assert ('fawebim gmask <mask:text>', 2) in usages
assert ('fawebim gpattern <pattern:text>', 2) in usages
assert ('fawebim count <mask:text>', 2) in usages
assert ('fawebim distr', 2) in usages
assert ('fawebim size', 2) in usages
assert ('fawebim center', 2) in usages
assert ('fawebim setbiome <biome:text>', 2) in usages
assert ('fawebim biomedistr', 2) in usages
assert ('fawebim gravity', 2) in usages
assert ('fawebim naturalize', 2) in usages
assert ('fawebim drain', 2) in usages
assert ('fawebim green', 2) in usages
assert ('fawebim snow', 2) in usages
assert ('fawebim thaw', 2) in usages
assert ('fawebim deform <expression:text>', 2) in usages
assert ('fawebim flood <x:integer> <y:integer> <z:integer> <radius:integer> <pattern:text>', 2) in usages
assert ('fawebim removenear <x:integer> <y:integer> <z:integer> <radius:integer> <mask:text>', 2) in usages
compatibility_aliases = [
    'ball <x:integer> <y:integer> <z:integer> <radius:number> <pattern:text>',
    'hball <x:integer> <y:integer> <z:integer> <radius:number> <pattern:text>',
    'cyl <x:integer> <y:integer> <z:integer> <radius:number> <height:integer> <pattern:text>',
    'hcyl <x:integer> <y:integer> <z:integer> <radius:number> <height:integer> <pattern:text>',
    'pyr <x:integer> <y:integer> <z:integer> <size:integer> <pattern:text>',
    'hpyr <x:integer> <y:integer> <z:integer> <size:integer> <pattern:text>',
    'segment <x1:integer> <y1:integer> <z1:integer> <x2:integer> <y2:integer> <z2:integer> <pattern:text>',
    'sides <pattern:text>', 'outline <pattern:text>', 'surface <pattern:text>', 'shell <pattern:text>',
    'blend [iterations:integer]', 'erosion [faces:integer] [iterations:integer]',
    'dilation [faces:integer] [iterations:integer]', 'fall', 'natural', 'deliquid', 'grassify',
    'snowfall', 'melt', 'distribution', 'dimensions', 'midpoint', 'rollback [steps:integer]',
    'reapply [steps:integer]', 'purgehistory', 'duplicate', 'clipclear',
    'cliprotate <degrees:integer>', 'clipflip <axis:text>', 'paint <block:text>',
    'substitute <from:text> <to:text>', 'shiftblocks <dx:integer> <dy:integer> <dz:integer>',
    'repeat <count:integer> <dx:integer> <dy:integer> <dz:integer>', 'paintbiome <biome:text>',
    'nearclear <x:integer> <y:integer> <z:integer> <radius:integer> <mask:text>',
    'connected <x:integer> <y:integer> <z:integer> <radius:integer> <pattern:text>',
]
for alias in compatibility_aliases:
    assert (f'fawebim {alias}', 2) in usages, f'missing implemented compatibility command: {alias}'
assert len(compatibility_aliases) >= 30
assert ('fawebim expand <dx:integer> <dy:integer> <dz:integer>', 2) in usages
assert ('fawebim contract <dx:integer> <dy:integer> <dz:integer>', 2) in usages
assert ('fawebim shift <dx:integer> <dy:integer> <dz:integer>', 2) in usages
assert ('fawebim outset <amount:integer>', 2) in usages
assert ('fawebim inset <amount:integer>', 2) in usages
assert ('fawebim undo [steps:integer]', 2) in usages
assert ('fawebim redo [steps:integer]', 2) in usages
assert ('fawebim clearhistory', 2) in usages

def encode_command(literals, arguments):
    req = bytearray()
    req += (1).to_bytes(4, 'little')          # CommandSource::Console
    req += (4).to_bytes(4, 'little')          # PermissionLevel::Owners
    req += len(literals).to_bytes(4, 'little')
    for word in literals:
        encoded = word.encode()
        req += len(encoded).to_bytes(4, 'little') + encoded
    req += len(arguments).to_bytes(4, 'little')
    for name, kind, value in arguments:
        encoded_name = name.encode()
        req += len(encoded_name).to_bytes(4, 'little') + encoded_name
        if kind == 'string':
            encoded_val = value.encode()
            req += (0).to_bytes(4, 'little') + len(encoded_val).to_bytes(4, 'little') + encoded_val
        elif kind == 'integer':
            req += (1).to_bytes(4, 'little') + int(value).to_bytes(4, 'little', signed=True)
        else:
            raise ValueError(kind)
    req += (0).to_bytes(4, 'little')          # DimensionKey(minecraft:overworld)
    req += b'\x00' * 24                       # position [f64; 3] = (0, 0, 0)
    req += b'\x00'                            # cancelled = false
    return bytes(req)

# 3) A real execution of /fawebim calc 1+2*3 by the console, decoded the way
# the game would send it, expecting Flow::Cancel (1) and the uncancelled flag.
reply = call_hook(
    store, memory, exports, HOOK_RUN_MOD_COMMAND,
    encode_command(['fawebim', 'calc'], [('formula', 'string', '1+2*3')])
)
flow, off = take_u32(reply, 0)
assert flow == 1, f'expected Flow::Cancel (1), got {flow}'
assert reply[off] == 0, 'the mod must not cancel the event flag'
assert off + 1 == len(reply), f'trailing bytes in the command reply ({len(reply) - off - 1})'
assert len(feedback_calls) == 1, f'expected exactly one feedback call, got {len(feedback_calls)}'
assert b'fawebim calc: 1+2*3 = 7' in feedback_calls[0], \
    f'the feedback line did not carry the computed value: {feedback_calls[0]!r}'
print('run_mod_command: /fawebim calc 1+2*3 -> Flow::Cancel, feedback carries "fawebim calc: 1+2*3 = 7"')

# 4) Mark a 4x5x4 selection via pos1/pos2, inspect via sel, fill via set stone.
for sub, coords in [('pos1', (0, 64, 0)), ('pos2', (3, 68, 3))]:
    reply = call_hook(
        store, memory, exports, HOOK_RUN_MOD_COMMAND,
        encode_command(['fawebim', sub], [('x', 'integer', coords[0]), ('y', 'integer', coords[1]), ('z', 'integer', coords[2])])
    )
    assert take_u32(reply, 0)[0] == 1

reply = call_hook(store, memory, exports, HOOK_RUN_MOD_COMMAND, encode_command(['fawebim', 'sel'], []))
assert take_u32(reply, 0)[0] == 1
assert b'(0, 64, 0) -> (3, 68, 3) (cuboid: 4x5x4)' in feedback_calls[-1], \
    f'unexpected sel feedback: {feedback_calls[-1]!r}'

reply = call_hook(
    store, memory, exports, HOOK_RUN_MOD_COMMAND,
    encode_command(['fawebim', 'set'], [('block', 'string', 'stone')])
)
assert take_u32(reply, 0)[0] == 1
assert len(edit_calls) == 1, f'expected 1 edit_blocks call, got {len(edit_calls)}'
assert b'fawebim set: 80 block(s) set with stone' in feedback_calls[-1], \
    f'unexpected set feedback: {feedback_calls[-1]!r}'
print('run_mod_command: pos1 + pos2 + sel + set stone -> edit_blocks(16 runs), 80 blocks set')

# 5) Switch selector to sphere, mark center + radius, and fill via set stone.
reply = call_hook(
    store, memory, exports, HOOK_RUN_MOD_COMMAND,
    encode_command(['fawebim', 'sel'], [('mode', 'string', 'sphere')])
)
assert take_u32(reply, 0)[0] == 1
assert b'fawebim sel: sphere' in feedback_calls[-1], f'unexpected sphere sel feedback: {feedback_calls[-1]!r}'
for sub, coords in [('pos1', (0, 64, 0)), ('pos2', (2, 64, 0))]:
    reply = call_hook(
        store, memory, exports, HOOK_RUN_MOD_COMMAND,
        encode_command(['fawebim', sub], [('x', 'integer', coords[0]), ('y', 'integer', coords[1]), ('z', 'integer', coords[2])])
    )
    assert take_u32(reply, 0)[0] == 1
reply = call_hook(
    store, memory, exports, HOOK_RUN_MOD_COMMAND,
    encode_command(['fawebim', 'set'], [('block', 'string', 'stone')])
)
assert take_u32(reply, 0)[0] == 1
assert len(edit_calls) == 2
assert b'block(s) set with stone' in feedback_calls[-1], f'unexpected sphere set feedback: {feedback_calls[-1]!r}'
print('run_mod_command: sel sphere + pos1 + pos2 + set stone -> edit_blocks OK')

# 6) Undo and redo the sphere edit via History + replay_history_record.
reply = call_hook(store, memory, exports, HOOK_RUN_MOD_COMMAND, encode_command(['fawebim', 'undo'], []))
assert take_u32(reply, 0)[0] == 1
assert len(edit_calls) == 3
assert b'fawebim undo: 1 edit(s) undone' in feedback_calls[-1], f'unexpected undo feedback: {feedback_calls[-1]!r}'

reply = call_hook(store, memory, exports, HOOK_RUN_MOD_COMMAND, encode_command(['fawebim', 'redo'], []))
assert take_u32(reply, 0)[0] == 1
assert len(edit_calls) == 4
assert b'fawebim redo: 1 edit(s) redone' in feedback_calls[-1], f'unexpected redo feedback: {feedback_calls[-1]!r}'
print('run_mod_command: undo + redo -> edit_blocks OK')

print('hook smoke check OK')

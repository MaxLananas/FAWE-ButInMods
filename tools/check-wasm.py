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
memory = wasmtime.Memory(store, memtype)
table = wasmtime.Table(store, tabletype, wasmtime.Val.ref_null(wasmtime.ValType.funcref()))
linker.define(store, 'env', 'memory', memory)
linker.define(store, 'env', '__indirect_function_table', table)

i32 = wasmtime.ValType.i32()
def global_i32(mutable, init=0):
    return wasmtime.Global(store, wasmtime.GlobalType(i32, mutable), wasmtime.Val.i32(init))

linker.define(store, 'env', '__stack_pointer', global_i32(True, memtype.limits.min * 65536 // 2))
linker.define(store, 'env', '__memory_base', global_i32(False, 0))
linker.define(store, 'env', '__table_base', global_i32(False, 0))
linker.define(store, 'GOT.mem', '__heap_base', global_i32(True, memtype.limits.min * 65536))
linker.define(store, 'GOT.mem', '__heap_end', global_i32(True, memtype.limits.min * 65536))
linker.define(store, 'GOT.mem', 'horizon_group_state', global_i32(True, 0))

# The guest reaches the game through one import: `host(id, in_ptr, in_len, out_ptr, out_cap)`.
# Every call the mod may make from its hooks so far is answered with the canned success the game
# would give; anything else raises, which surfaces as a trap naming the import id, instead of a
# guest-side `.expect()` panic at a wrong-typed (empty) answer.
HOST_COMMANDS_FEEDBACK = 1133  # Commands::feedback — answered with Result<(), TextFormError>::Ok(())
feedback_calls = []

def host_handler(id_, in_ptr, in_len, out_ptr, out_cap):
    args = bytes(memory.read(store, in_ptr, in_ptr + in_len))
    if id_ == HOST_COMMANDS_FEEDBACK:
        feedback_calls.append(args)
        answer = b'\x00'  # Result::Ok — u8 tag 0, unit payload encodes to nothing
        if len(answer) > out_cap:
            return len(answer)  # guest calls host_reply to fetch the rest
        memory.write(store, bytearray(answer), out_ptr)
        return len(answer)
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

# 3) A real execution of /fawebim calc 1+2*3 by the console, decoded the way
# the game would send it, expecting Flow::Cancel (1) and the uncancelled flag.
request = bytearray()
request += (1).to_bytes(4, 'little')      # CommandSource::Console
request += (4).to_bytes(4, 'little')      # PermissionLevel::Owners
literals = ['fawebim', 'calc']
request += len(literals).to_bytes(4, 'little')
for word in literals:
    encoded = word.encode()
    request += len(encoded).to_bytes(4, 'little') + encoded
# one argument: formula = "1+2*3"
argument_name = 'formula'.encode()
formula = '1+2*3'.encode()
request += (1).to_bytes(4, 'little')      # arguments count
request += len(argument_name).to_bytes(4, 'little') + argument_name
request += (0).to_bytes(4, 'little')      # CommandArgumentValue::String
request += len(formula).to_bytes(4, 'little') + formula
request += (0).to_bytes(4, 'little')      # DimensionKey(minecraft:overworld placeholder)
request += b'\x00' * 24                # position [f64; 3] = (0, 0, 0)
request += b'\x00'                        # cancelled = false
reply = call_hook(store, memory, exports, HOOK_RUN_MOD_COMMAND, bytes(request))
flow, off = take_u32(reply, 0)
assert flow == 1, f'expected Flow::Cancel (1), got {flow}'
assert reply[off] == 0, 'the mod must not cancel the event flag'
assert off + 1 == len(reply), f'trailing bytes in the command reply ({len(reply) - off - 1})'
assert len(feedback_calls) == 1, f'expected exactly one feedback call, got {len(feedback_calls)}'
assert b'fawebim calc: 1+2*3 = 7' in feedback_calls[0], \
    f'the feedback line did not carry the computed value: {feedback_calls[0]!r}'
print('run_mod_command: /fawebim calc 1+2*3 -> Flow::Cancel, feedback carries "fawebim calc: 1+2*3 = 7"')

print('hook smoke check OK')

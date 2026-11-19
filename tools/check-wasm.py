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

host_type = wasmtime.FuncType([i32] * 5, [i32])
linker.define(store, 'horizon', 'host', wasmtime.Func(store, host_type, lambda *a: 0))
reply_type = wasmtime.FuncType([i32], [])
linker.define(store, 'horizon', 'host_reply', wasmtime.Func(store, reply_type, lambda *a: None))

instance = linker.instantiate(store, module)
out = instance.exports(store)
out.get('__wasm_apply_data_relocs')(store)
layout_a = out.get('horizon_layout')(store)
layout_b = out.get('horizon_layout')(store)
assert layout_a == layout_b, 'horizon_layout() is not deterministic'
print(f'instantiated OK; horizon_layout() = 0x{layout_a & 0xFFFFFFFFFFFFFFFF:016x} (stable)')

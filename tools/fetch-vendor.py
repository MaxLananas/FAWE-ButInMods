#!/usr/bin/env python3
"""Fetch the crates.io packages the Horizon mod build needs into .vendor/.

The sandbox this was written in has no route to crates.io, but GitHub and npm
are reachable, so every crate is pulled as a tarball from its repository (the
locations and tags below were verified against the crates.io index snapshot in
Horizon's Cargo.lock). `.cargo/config.toml` then replaces the crates-io source
with the resulting directory source, so the build works fully offline.

Path-dependency crates (the Horizon SDK) are not fetched here; see
tools/fetch-sdk.sh.
"""
import itertools
import json
import os
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
VENDOR = os.path.join(ROOT, '.vendor')
STAGE = os.path.join(ROOT, '.vendor-stage')

# name -> (repo, tag, subdir-within-repo or None)
F = {
    'allocator-api2': ('zakarumych/allocator-api2', None, None),
    'anyhow': ('dtolnay/anyhow', None, None),
    'bitflags': ('bitflags/bitflags', None, None),
    'block-buffer': ('RustCrypto/utils', 'block-buffer-v0.10.4', 'block-buffer'),
    'bumpalo': ('fitzgen/bumpalo', None, None),
    'bytemuck': ('Lokathor/bytemuck', 'v1.25.2', None),
    'bytemuck_derive': ('Lokathor/bytemuck', 'v1.25.2', 'derive'),
    'cfg-if': ('rust-lang/cfg-if', None, None),
    'cpufeatures': ('RustCrypto/utils', 'cpufeatures-v0.2.17', 'cpufeatures'),
    'crypto-common': ('RustCrypto/traits', 'crypto-common-v0.1.7', 'crypto-common'),
    'digest': ('RustCrypto/traits', 'digest-v0.10.7', 'digest'),
    'equivalent': ('indexmap-rs/equivalent', None, None),
    'foldhash': ('orlp/foldhash', None, None),
    'futures-channel': ('rust-lang/futures-rs', None, 'futures-channel'),
    'futures-core': ('rust-lang/futures-rs', None, 'futures-core'),
    'futures-io': ('rust-lang/futures-rs', None, 'futures-io'),
    'futures-sink': ('rust-lang/futures-rs', None, 'futures-sink'),
    'futures-task': ('rust-lang/futures-rs', None, 'futures-task'),
    'futures-util': ('rust-lang/futures-rs', None, 'futures-util'),
    'getrandom': ('rust-random/getrandom', 'v0.2.17', None),
    'glam': ('bitshifter/glam-rs', '0.30.10', None),
    'hashbrown': ('rust-lang/hashbrown', 'v0.17.1', None),
    # master, pinned below to the 0.5.0 release commit; the repo has no tags
    'heck': ('withoutboats/heck', 'ref:252c1906cefefb8d13ecad7fce7b8f27ce6437e0', None),
    'indexmap': ('indexmap-rs/indexmap', '2.14.2', None),
    'itoa': ('dtolnay/itoa', None, None),
    # The wasi_snapshot_preview1 bindings are only built for wasi targets; shimmed
    'js-sys': ('rustwasm/wasm-bindgen', '0.2.129', 'crates/js-sys'),
    'libc': ('rust-lang/libc', '0.2.190', None),
    # libm moved into compiler-builtins; pinned at the commit carrying libm 0.2.16
    'libm': ('rust-lang/compiler-builtins', 'ref:8fe310e552c1a24df0bd977e87756168fe396037', 'libm'),
    'log': ('rust-lang/log', '0.4.34', None),
    'memchr': ('BurntSushi/memchr', '2.8.3', None),
    'once_cell': ('matklad/once_cell', 'v1.21.4', None),
    'phf': ('rust-phf/rust-phf', None, 'phf'),
    'phf_codegen': ('rust-phf/rust-phf', None, 'phf_codegen'),
    'phf_generator': ('rust-phf/rust-phf', None, 'phf_generator'),
    'phf_shared': ('rust-phf/rust-phf', None, 'phf_shared'),
    'pin-project-lite': ('taiki-e/pin-project-lite', None, None),
    'ppv-lite86': ('cryptocorrosion/cryptocorrosion', 'ppv-lite86-0.2.21', 'utils-simd/ppv-lite86'),
    'prettyplease': ('dtolnay/prettyplease', None, None),
    'proc-macro2': ('dtolnay/proc-macro2', None, None),
    'quote': ('dtolnay/quote', None, None),
    'rand': ('rust-random/rand', '0.8.8', None),
    'rand_chacha': ('rust-random/rand', '0.8.8', 'rand_chacha'),
    'rand_core': ('rust-random/rand', '0.8.8', 'rand_core'),
    'rustversion': ('dtolnay/rustversion', None, None),
    'semver': ('dtolnay/semver', None, None),
    'serde': ('serde-rs/serde', 'v1.0.229', 'serde'),
    'serde_core': ('serde-rs/serde', 'v1.0.229', 'serde_core'),
    'serde_derive': ('serde-rs/serde', 'v1.0.229', 'serde_derive'),
    'serde_json': ('serde-rs/json', 'v1.0.151', None),
    'serde_spanned': ('toml-rs/toml', 'serde_spanned-v1.1.2', 'crates/serde_spanned'),
    'sha2': ('RustCrypto/hashes', 'sha2-v0.10.9', 'sha2'),
    'siphasher': ('jedisct1/rust-siphash', '1.0.4', None),
    'slab': ('tokio-rs/slab', None, None),
    'subtle': ('dalek-cryptography/subtle', None, None),
    # syn is fetched in both versions by fetch_syn() below
    'toml': ('toml-rs/toml', 'toml-v1.1.6', 'crates/toml'),
    'toml_datetime': ('toml-rs/toml', 'toml_datetime-v1.1.1', 'crates/toml_datetime'),
    'toml_parser': ('toml-rs/toml', 'toml_parser-v1.1.3', 'crates/toml_parser'),
    'toml_writer': ('toml-rs/toml', 'toml_writer-v1.1.3', 'crates/toml_writer'),
    'typenum': ('paholg/typenum', None, None),
    'unicode-ident': ('dtolnay/unicode-ident', None, None),
    'version_check': ('SergioBenitez/version_check', None, None),
    'zerocopy': ('google/zerocopy', 'v0.8.60', 'zerocopy'),
    'zerocopy-derive': ('google/zerocopy', 'v0.8.60', 'zerocopy/zerocopy-derive'),
    'zmij': ('dtolnay/zmij', '1.0.23', None),
    'wasm-bindgen': ('rustwasm/wasm-bindgen', '0.2.129', None),
    'wasm-bindgen-macro': ('rustwasm/wasm-bindgen', '0.2.129', 'crates/macro'),
    'wasm-bindgen-macro-support': ('rustwasm/wasm-bindgen', '0.2.129', 'crates/macro-support'),
    'wasm-bindgen-shared': ('rustwasm/wasm-bindgen', '0.2.129', 'crates/shared'),
    'winnow': ('winnow-rs/winnow', 'v1.0.4', None),
}

# Crates whose source is a shim in tools/shims (copied instead of fetched).
SHIMS = ['unicode_names2', 'wit-parser', 'wasi']

# Crates with more than one vendored version; every one gets a `name@version` dir.
MULTI_VERSION = {'syn'}

# Expected versions, from Horizon's Cargo.lock (the mod builds its own fresh
# lock, but the vendored source should carry the same releases).
EXPECTED = {
    'allocator-api2': '0.2.21', 'anyhow': '1.0.104', 'bitflags': '2.13.2',
    'block-buffer': '0.10.4', 'bumpalo': '3.20.3', 'bytemuck': '1.25.2',
    'bytemuck_derive': '1.11.0', 'cfg-if': '1.0.5', 'cpufeatures': '0.2.17',
    'crypto-common': '0.1.7', 'digest': '0.10.7', 'equivalent': '1.0.2',
    'foldhash': '0.2.0', 'futures-channel': '0.3.34', 'futures-core': '0.3.34',
    'futures-io': '0.3.34', 'futures-sink': '0.3.34', 'futures-task': '0.3.34',
    'futures-util': '0.3.34', 'getrandom': '0.2.17', 'glam': '0.30.10',
    'hashbrown': '0.17.1', 'heck': '0.5.0', 'indexmap': '2.14.2', 'itoa': '1.0.18',
    'js-sys': '0.3.106', 'libc': '0.2.190', 'libm': '0.2.16', 'log': '0.4.34',
    'memchr': '2.8.3', 'once_cell': '1.21.4', 'phf': '0.11.3', 'phf_codegen': '0.11.3',
    'phf_generator': '0.11.3', 'phf_shared': '0.11.3', 'pin-project-lite': '0.2.17',
    'ppv-lite86': '0.2.21', 'prettyplease': '0.2.37', 'proc-macro2': '1.0.107',
    'quote': '1.0.47', 'rand': '0.8.8', 'rand_chacha': '0.3.1', 'rand_core': '0.6.4',
    'rustversion': '1.0.23', 'semver': '1.0.28', 'serde': '1.0.229',
    'serde_core': '1.0.229', 'serde_derive': '1.0.229', 'serde_json': '1.0.151',
    'serde_spanned': '1.1.2', 'sha2': '0.10.9', 'siphasher': '1.0.4',
    'slab': '0.4.12', 'subtle': '2.6.1', 'toml': '1.1.6', 'toml_datetime': '1.1.1',
    'toml_parser': '1.1.3', 'toml_writer': '1.1.3', 'typenum': '1.20.1',
    'unicode-ident': '1.0.26', 'version_check': '0.9.5', 'zerocopy': '0.8.60',
    'zerocopy-derive': '0.8.60', 'zmij': '1.0.23', 'wasm-bindgen': '0.2.129',
    'wasm-bindgen-macro': '0.2.129', 'wasm-bindgen-macro-support': '0.2.129',
    'wasm-bindgen-shared': '0.2.129', 'winnow': '1.0.4',
}

def log(msg):
    print(msg, flush=True)

def safe_extractall(tar, path):
    base = os.path.realpath(path)
    for member in tar.getmembers():
        target = os.path.realpath(os.path.join(base, member.name))
        if not (target == base or target.startswith(base + os.sep)):
            raise ValueError(f'unsafe tar member: {member.name}')
    tar.extractall(path)

def fetch_tarball(url):
    try:
        with urllib.request.urlopen(url, timeout=120) as response:
            if response.status != 200:
                return None
            return response.read()
    except Exception:
        return None

def tag_candidates(name, expected):
    ver = expected.split('+')[0]
    yield f'v{ver}'
    yield ver
    yield f'{name}-v{ver}'
    yield f'{name}-{ver}'
    yield f'{name}_v{ver}'
    yield f'{name}_{ver}'

def package_name_of(path):
    try:
        m = parse_toml(path)
    except Exception:
        return None
    return (m.get('package') or {}).get('name')

def find_crate_dir(root, name):
    """Return the directory inside `root` whose Cargo.toml declares package `name`."""
    matches = []
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in ('.git', 'target', 'node_modules')]
        if 'Cargo.toml' not in filenames:
            continue
        path = os.path.join(dirpath, 'Cargo.toml')
        if package_name_of(path) == name:
            rel = os.path.relpath(dirpath, root)
            depth = 0 if rel == '.' else rel.count(os.sep) + 1
            matches.append((depth, dirpath))
    if not matches:
        return None
    matches.sort(key=lambda item: item[0])
    return matches[0][1]

# Exact text replacements for manifests the upstream repo ships broken for a
# standalone directory source. Each maps crate -> (old, new).
PATCHES = {
    # Feature names an optional dev-dependency the git repo does not carry;
    # internal to bumpalo's benches, never enabled here.
    'bumpalo': ('bench_allocator_api = ["allocator_api", "blink-alloc/nightly"]',
                'bench_allocator_api = ["allocator_api"]'),
    # bumpalo was a dev-dependency; stripped for the directory source.
    'hashbrown': ('nightly = ["foldhash?/nightly", "bumpalo/allocator_api"]',
                  'nightly = ["foldhash?/nightly"]'),
    # Internal test-only feature; wasm-bindgen-test is not vendored.
    'wasm-bindgen': ('gg-alloc = ["wasm-bindgen-test/gg-alloc"]', 'gg-alloc = []'),
    # syn-test-suite is a dev-dependency; feature is never enabled here.
    'syn': ('test = ["syn-test-suite/all-features"]', 'test = []'),
}

def apply_patches(name, text):
    patch = PATCHES.get(name)
    if patch and patch[0] in text:
        text = text.replace(patch[0], patch[1])
    return text

def strip_dev_dependencies(text):
    """Drop [dev-dependencies] tables: a directory source never builds them."""
    out = []
    skipping = False
    for line in text.split('\n'):
        stripped = line.strip()
        if stripped.startswith('['):
            name = stripped.strip('[]').strip()
            head = name.split('.', 1)[0].strip('\"\'') if not name.startswith('target') else name
            is_dev = (
                name == 'dev-dependencies'
                or name.endswith('.dev-dependencies')
                or name.endswith('dev-dependencies]')
                or (name.startswith('target.') and name.rsplit('.', 1)[-1].strip('\"\'') == 'dev-dependencies')
            )
            # `[target.'cfg(...)'.dev-dependencies]` ends with `.dev-dependencies]`
            if name.startswith('target.') and "dev-dependencies]" in name:
                is_dev = True
            if is_dev:
                skipping = True
                continue
            skipping = False
            out.append(line)
            continue
        if skipping:
            continue
        out.append(line)
    return '\n'.join(out)

def parse_toml(path):
    import tomllib
    with open(path, 'rb') as handle:
        return tomllib.load(handle)

def toml_value(value):
    if isinstance(value, bool):
        return 'true' if value else 'false'
    if isinstance(value, str):
        return f'"{value}"'
    if isinstance(value, int):
        return str(value)
    if isinstance(value, float):
        return repr(value)
    if isinstance(value, list):
        return '[' + ', '.join(toml_value(v) for v in value) + ']'
    if isinstance(value, dict):
        return '{ ' + ', '.join(f'{k} = {toml_value(v)}' for k, v in value.items()) + ' }'
    return repr(value)

def resolve_workspace_dep(name, extra, workspace_deps):
    base = workspace_deps.get(name)
    if base is None:
        return None
    merged = dict(base) if isinstance(base, dict) else {'version': base}
    merged.pop('path', None)
    for key, value in extra.items():
        if key != 'workspace':
            merged[key] = value
    return merged

def split_inline_body(body):
    parts, depth, current = [], 0, ''
    for ch in body:
        if ch in '[{':
            depth += 1
            current += ch
        elif ch in ']}':
            depth -= 1
            current += ch
        elif ch == ',' and depth == 0:
            parts.append(current)
            current = ''
        else:
            current += ch
    if current.strip():
        parts.append(current)
    return [p.strip() for p in parts if p.strip()]

BRACE = re.compile(r'^(\s*)([A-Za-z0-9_-]+)\s*=\s*\{([^{}]*(\{[^{}]*\}[^{}]*)*)\}\s*$')
DOTTED = re.compile(r'^(\s*)([A-Za-z0-9_-]+)\.workspace\s*=\s*true\s*$')
SECTION = re.compile(r'^\s*\[([^\[\]]+)\]\s*$')

def rewrite_manifest_text(text, workspace_package, workspace_deps):
    out = []
    section = ''
    drop_section = False
    for line in text.splitlines():
        header = SECTION.match(line)
        if header:
            section = header.group(1).strip()
            drop_section = section in ('lints',) or section.startswith('lints.') \
                or section in ('workspace', 'workspace.package', 'workspace.dependencies', 'workspace.lints', 'workspace.metadata')
            if not drop_section:
                out.append(line)
            continue
        if drop_section:
            continue
        in_deps = section.endswith('dependencies') or section in (
            'dependencies', 'build-dependencies', 'dev-dependencies')
        dotted = DOTTED.match(line)
        if dotted:
            indent, key = dotted.group(1), dotted.group(2)
            if in_deps:
                resolved = resolve_workspace_dep(key, {}, workspace_deps)
                if resolved is None:
                    out.append(line)
                    continue
                out.append(f'{indent}{key} = {toml_value(resolved)}')
            else:
                value = workspace_package.get(key)
                if value is None and key == 'version':
                    value = workspace_package.get('version')
                if value is None:
                    out.append(line)
                else:
                    out.append(f'{indent}{key} = {toml_value(value)}')
            continue
        brace = BRACE.match(line)
        if brace:
            indent, key, body = brace.group(1), brace.group(2), brace.group(3)
            parts = split_inline_body(body)
            fields = {}
            bare = []
            for part in parts:
                if '=' in part:
                    k, v = part.split('=', 1)
                    fields[k.strip()] = v.strip()
                else:
                    bare.append(part)
            if fields.get('workspace') == 'true' or bare == ['workspace = true'] or (
                'workspace' in fields and fields['workspace'] == 'true'):
                if in_deps:
                    extra = {k: parse_inline_scalar(v) for k, v in fields.items() if k != 'workspace'}
                    resolved = resolve_workspace_dep(key, extra, workspace_deps)
                    if resolved is None:
                        out.append(line)
                        continue
                    out.append(f'{indent}{key} = {toml_value(resolved)}')
                    continue
            else:
                # drop path = "..." from any dependency entry
                if in_deps and 'path' in fields:
                    fields.pop('path')
                    body2 = ', '.join(f'{k} = {v}' for k, v in fields.items())
                    out.append(f'{indent}{key} = {{ {body2} }}')
                    continue
            out.append(line)
            continue
        out.append(line)
    return '\n'.join(out) + '\n'

def parse_inline_scalar(text):
    text = text.strip()
    if text.startswith('['):
        return [coerce(item.strip()) for item in split_inline_body(text.strip('[]'))]
    return coerce(text)

def coerce(text):
    if isinstance(text, (list, bool, int, float)):
        return text
    text = text.strip()
    if text == 'true': return True
    if text == 'false': return False
    if text.startswith('"') and text.endswith('"'): return text[1:-1]
    if text.startswith("'") and text.endswith("'"): return text[1:-1]
    try:
        return int(text)
    except ValueError:
        try:
            return float(text)
        except ValueError:
            return text

def copy_crate(src_dir, dest_dir, repo_root, name, expected):
    os.makedirs(dest_dir, exist_ok=True)
    for item in os.listdir(src_dir):
        if item == '.git':
            continue
        s = os.path.join(src_dir, item)
        d = os.path.join(dest_dir, item)
        if os.path.isdir(s):
            shutil.copytree(s, d, symlinks=True, ignore=shutil.ignore_patterns('.git'))
        else:
            shutil.copy2(s, d)
    root_manifest_path = os.path.join(repo_root, 'Cargo.toml')
    root_manifest = parse_toml(root_manifest_path) if os.path.exists(root_manifest_path) else {}
    workspace_package = (root_manifest.get('workspace') or {}).get('package') or {}
    workspace_deps = (root_manifest.get('workspace') or {}).get('dependencies') or {}
    manifest_path = os.path.join(dest_dir, 'Cargo.toml')
    text = open(manifest_path, encoding='utf-8').read()
    new_text = rewrite_manifest_text(text, workspace_package, workspace_deps)
    new_text = strip_dev_dependencies(new_text)
    new_text = apply_patches(name, new_text)
    open(manifest_path, 'w').write(new_text)
    version = parse_toml(manifest_path).get('package', {}).get('version', '')
    with open(os.path.join(dest_dir, '.cargo-checksum.json'), 'w') as handle:
        handle.write('{"files":{}}\n')
    return version, None

def fetch_syn():
    for ver in ('2.0.119', '3.0.6'):
        dest = os.path.join(VENDOR, f'syn@{ver}')
        if os.path.exists(dest) and not os.path.exists(os.path.join(dest, 'Cargo.toml')):
            shutil.rmtree(dest)
        if os.path.exists(dest):
            log(f'syn@{ver}: cached')
            continue
        data = fetch_tarball(f'https://codeload.github.com/dtolnay/syn/tar.gz/refs/tags/{ver}')
        if data is None:
            log(f'syn@{ver}: FAILED')
            return False
        with tempfile.TemporaryDirectory(dir=STAGE) as tmp:
            tar_path = os.path.join(tmp, 'crate.tar.gz')
            open(tar_path, 'wb').write(data)
            with tarfile.open(tar_path) as tar:
                safe_extractall(tar, tmp)
            repo_root = [os.path.join(tmp, i) for i in os.listdir(tmp) if os.path.isdir(os.path.join(tmp, i))][0]
            crate_dir = find_crate_dir(repo_root, 'syn')
            version, changes = copy_crate(crate_dir, dest, repo_root, 'syn', ver)
            log(f'syn@{ver}: ok {version}')
    return True

def fetch_generic_array():
    dest = os.path.join(VENDOR, 'generic-array')
    if os.path.exists(dest):
        log('generic-array: cached')
        return True
    url = 'https://codeload.github.com/BayLibre/android_external_rust_crates_generic-array/tar.gz/refs/heads/android-17'
    data = fetch_tarball(url)
    if data is None:
        log('generic-array: FAILED (AOSP mirror unreachable)')
        return False
    with tempfile.TemporaryDirectory(dir=STAGE) as tmp:
        tar_path = os.path.join(tmp, 'crate.tar.gz')
        open(tar_path, 'wb').write(data)
        with tarfile.open(tar_path) as tar:
            safe_extractall(tar, tmp)
        repo_root = [os.path.join(tmp, i) for i in os.listdir(tmp) if os.path.isdir(os.path.join(tmp, i))][0]
        crate_dir = find_crate_dir(repo_root, 'generic-array')
        if crate_dir is None:
            log('generic-array: not found in AOSP tarball')
            return False
        version, changes = copy_crate(crate_dir, dest, repo_root, 'generic-array', '0.14.7')
        # crypto-common pins `=0.14.7`; the AOSP snapshot carries 0.14.9, which is
        # the same code with fixes, so re-label it to satisfy the pin.
        manifest_path = os.path.join(dest, 'Cargo.toml')
        text = open(manifest_path).read()
        text = re.sub(r'(?m)^version = "0\.14\.9"$', 'version = "0.14.7"', text)
        open(manifest_path, 'w').write(text)
        log('generic-array: ok 0.14.7 (re-labelled from 0.14.9 for the crypto-common pin)')
    return True

def vendor_one(name, spec, expected):
    repo, tag, subdir = spec
    dest_name = name
    if name in MULTI_VERSION:
        dest_name = f'{name}@{expected}'
    dest = os.path.join(VENDOR, dest_name)
    if os.path.exists(dest):
        return 'cached', None
    tags = [tag] if tag else list(tag_candidates(name, expected))
    got = None
    for candidate in tags:
        if candidate and candidate.startswith('ref:'):
            url = f'https://codeload.github.com/{repo}/tar.gz/{candidate[4:]}'
        else:
            url = f'https://codeload.github.com/{repo}/tar.gz/refs/tags/{candidate}' if candidate else None
        if not url:
            continue
        log(f'  {name}: trying {repo}@{candidate}')
        data = fetch_tarball(url)
        if data is None:
            continue
        with tempfile.TemporaryDirectory(dir=STAGE) as tmp:
            tar_path = os.path.join(tmp, 'crate.tar.gz')
            with open(tar_path, 'wb') as handle:
                handle.write(data)
            try:
                with tarfile.open(tar_path) as tar:
                    safe_extractall(tar, tmp)
            except Exception as exc:
                log(f'  {name}: extract failed ({exc})')
                continue
            roots = [os.path.join(tmp, item) for item in os.listdir(tmp) if os.path.isdir(os.path.join(tmp, item))]
            if len(roots) != 1:
                log(f'  {name}: unexpected tarball layout')
                continue
            repo_root = roots[0]
            if subdir:
                crate_dir = os.path.join(repo_root, subdir)
                if not os.path.isdir(crate_dir):
                    log(f'  {name}: subdir {subdir} not in tarball')
                    continue
            else:
                crate_dir = find_crate_dir(repo_root, name)
                if crate_dir is None:
                    log(f'  {name}: crate not found in tarball')
                    continue
            version, changes = copy_crate(crate_dir, dest, repo_root, name, expected)
            got = (version, changes)
            break
    if got is None:
        return 'FAILED', None
    version, changes = got
    flag = ''
    if version and expected and not version.startswith(expected.split('+')[0]):
        flag = f' [version {version} != expected {expected}]'
    if changes:
        log(f'  {name}: manifest fixes: {", ".join(changes)}')
    return f'ok {version}{flag}', changes


# ---------------------------------------------------------------------------
# std (-Zbuild-std) closure, pinned to the sysroot's bundled source (1.99.0).
#
# tools/setup-wasm-toolchain.sh installs the toolchain and its bundled
# `library/Cargo.lock` (committed as tools/sysroot-library-Cargo.lock); this
# section vendors everything that lock names. The crates here are deliberately
# not mixed into F: the sysroot lockfile pins several crates at *older*
# versions than the SDK closure, so they live in sibling `name@version`
# directories.
# ---------------------------------------------------------------------------

STD_REAL = {
    'cfg-if@1.0.4': ('rust-lang/cfg-if', 'v1.0.4'),
    'libc@0.2.189': ('rust-lang/libc', '0.2.189'),
    'dlmalloc@0.2.14': ('rust-lang/dlmalloc-rs', '0.2.14'),
    'rustc-demangle@0.1.28': ('rust-lang/rustc-demangle', 'rustc-demangle-v0.1.28'),
    'object@0.39.1': ('gimli-rs/object', 'v0.39.1'),
    'addr2line@0.27.1': ('gimli-rs/addr2line', 'v0.27.1'),
    'gimli@0.34.0': ('gimli-rs/gimli', 'v0.34.0'),
    # no 0.9.1 tag upstream; 0.9.0 is byte-identical apart from the version line
    'miniz_oxide@0.9.1': ('Frommi/miniz_oxide', '0.9.0'),
}
STD_REAL_RELABEL = {'miniz_oxide@0.9.1': ('0.9.0', '0.9.1')}

# Hand-written, in tools/shims/<dir> (see each README for provenance).
STD_SHIMS = {
    'adler2@2.0.1': 'adler2',
    'rustc-literal-escaper@0.0.8': 'rustc-literal-escaper',
}

# Mentioned by the sysroot lockfile but never compiled for wasm32-unknown-unknown;
# resolution still requires the packages to exist. Each maps to feature names the
# lock requests on top of `rustc-dep-of-std` (all stubs carry it).
STD_STUBS = {
    'cc@1.2.0': [],
    'getopts@0.2.24': [],
    'shlex@1.3.0': [],
    'clap@4.3.21': [],
    'cpp_demangle@0.5.0': [],
    'crc32fast@1.2.0': [],
    'fallible-iterator@0.3.0': [],
    'flate2@1.0.30': [],
    'fnv@1.0.7': [],
    'memmap2@0.9.4': [],
    'object@0.40.0': [],
    'ruzstd@0.8.1': [],
    'simd-adler32@0.3.7': [],
    'smallvec@1.13.2': [],
    'stable_deref_trait@1.1.1': [],
    'typed-arena@2.0.1': [],
    'wasmparser@0.247.0': [],
    'fortanix-sgx-abi@0.6.1': [],
    'hermit-abi@0.5.2': [],
    'moto-rt@0.16.4': [],
    'r-efi@5.3.0': [],
    'r-efi-alloc@2.1.0': [],
    'unwinding@0.2.10': ['unwinder', 'fde-custom'],
    'vex-sdk@0.27.1': [],
    'wasip1@1.0.0': [],
    'wasip2@1.0.4+wasi-0.2.12': [],
    'wasip3@0.7.0+wasi-0.3.0': [],
    'wit-bindgen@0.57.1': [],
    'rand@0.9.5': ['alloc'],
    'rand_core@0.9.5': [],
    'rand_xorshift@0.4.0': [],
}


def std_stub_manifest(name, version, extra_features):
    features = ['default = []', 'rustc-dep-of-std = []']
    for feature in extra_features:
        features.append(f'{feature} = []')
    body = '\n'.join(features)
    return f'''[package]
name = "{name}"
version = "{version}"
edition = "2021"
license = "MIT OR Apache-2.0"
description = "Stub crate: not compiled for wasm32-unknown-unknown (see horizon/README.md)."

[lib]
path = "src/lib.rs"

[features]
{body}
'''


def load_sysroot_lock_checksums():
    lock_path = os.path.join(ROOT, 'tools', 'sysroot-library-Cargo.lock')
    checksums = {}
    name = None
    version = None
    with open(lock_path) as handle:
        for line in handle:
            line = line.strip()
            if line == '[[package]]':
                name = version = None
            elif line.startswith('name = "'):
                name = line.split('"')[1]
            elif line.startswith('version = "'):
                version = line.split('"')[1]
            elif line.startswith('checksum = "') and name and version:
                checksums[f'{name}@{version}'] = line.split('"')[1]
                name = version = None
    return checksums


def write_checksum(dest, package):
    with open(os.path.join(dest, '.cargo-checksum.json'), 'w') as handle:
        if package:
            handle.write('{"files":{},"package":"%s"}\n' % package)
        else:
            handle.write('{"files":{}}\n')


def fetch_std():
    checksums = load_sysroot_lock_checksums()
    failures = []
    for dest_name, (repo, tag) in sorted(STD_REAL.items()):
        dest = os.path.join(VENDOR, dest_name)
        if os.path.exists(dest):
            log(f'{dest_name}: cached')
            continue
        name = dest_name.split('@')[0]
        data = fetch_tarball(f'https://codeload.github.com/{repo}/tar.gz/refs/tags/{tag}')
        if data is None:
            log(f'{dest_name}: FAILED (tarball unreachable)')
            failures.append(dest_name)
            continue
        with tempfile.TemporaryDirectory(dir=STAGE) as tmp:
            tar_path = os.path.join(tmp, 'crate.tar.gz')
            with open(tar_path, 'wb') as handle:
                handle.write(data)
            with tarfile.open(tar_path) as tar:
                safe_extractall(tar, tmp)
            roots = [os.path.join(tmp, item) for item in os.listdir(tmp) if os.path.isdir(os.path.join(tmp, item))]
            if len(roots) != 1:
                log(f'{dest_name}: unexpected tarball layout')
                failures.append(dest_name)
                continue
            crate_dir = find_crate_dir(roots[0], name)
            if crate_dir is None:
                log(f'{dest_name}: crate not found in tarball')
                failures.append(dest_name)
                continue
            copy_crate(crate_dir, dest, roots[0], name, dest_name.split('@')[1])
            relabel = STD_REAL_RELABEL.get(dest_name)
            if relabel:
                manifest = os.path.join(dest, 'Cargo.toml')
                text = open(manifest).read()
                text = text.replace(f'version = "{relabel[0]}"', f'version = "{relabel[1]}"', 1)
                open(manifest, 'w').write(text)
        log(f'{dest_name}: ok')
    for dest_name, shim_dir in sorted(STD_SHIMS.items()):
        dest = os.path.join(VENDOR, dest_name)
        if os.path.exists(dest):
            log(f'{dest_name}: cached')
            continue
        src = os.path.join(ROOT, 'tools', 'shims', shim_dir)
        shutil.copytree(src, dest, ignore=shutil.ignore_patterns('README.md'))
        log(f'{dest_name}: shim installed')
    for dest_name, extras in sorted(STD_STUBS.items()):
        dest = os.path.join(VENDOR, dest_name)
        if os.path.exists(dest):
            continue
        name, version = dest_name.split('@', 1)
        os.makedirs(os.path.join(dest, 'src'))
        open(os.path.join(dest, 'Cargo.toml'), 'w').write(std_stub_manifest(name, version, extras))
        open(os.path.join(dest, 'src', 'lib.rs'), 'w').write('// Stub crate: see horizon/README.md.\n')
        log(f'{dest_name}: stub created')
    # Checksums: the sysroot lockfile carries registry checksums for most of the
    # real crates; it carries none for adler2/gimli (re-derived sources) and the
    # hand-written shims keep files-only manifests. Cargo only needs the
    # `package` value to match the lock.
    for dest_name in list(STD_REAL) + list(STD_SHIMS) + list(STD_STUBS):
        dest = os.path.join(VENDOR, dest_name)
        if os.path.exists(dest):
            write_checksum(dest, checksums.get(dest_name))
    return failures


def load_lock_checksums(lock_path):
    checksums = {}
    if not os.path.exists(lock_path):
        return checksums
    name = version = None
    with open(lock_path) as handle:
        for line in handle:
            line = line.strip()
            if line == '[[package]]':
                name = version = None
            elif line.startswith('name = "'):
                name = line.split('"')[1]
            elif line.startswith('version = "'):
                version = line.split('"')[1]
            elif line.startswith('checksum = "') and name and version:
                checksums[f'{name}@{version}'] = line.split('"')[1]
                name = version = None
    return checksums


def sync_all_checksums():
    """Make every vendored crate's `.cargo-checksum.json` consistent with the
    lockfiles cargo verifies against (the sysroot `library/Cargo.lock` for the
    std build and the mod's `horizon/Cargo.lock`). A crate that appears with a
    checksum there needs a matching `package` value in its directory; everything
    else stays files-only."""
    checksums = load_sysroot_lock_checksums()
    checksums.update(load_lock_checksums(os.path.join(ROOT, 'horizon', 'Cargo.lock')))
    updated = 0
    for entry in os.listdir(VENDOR):
        dest = os.path.join(VENDOR, entry)
        if not os.path.isdir(dest):
            continue
        manifest_path = os.path.join(dest, 'Cargo.toml')
        if not os.path.exists(manifest_path):
            continue
        try:
            manifest = parse_toml(manifest_path)
        except Exception:
            continue
        package = manifest.get('package') or {}
        name, version = package.get('name'), package.get('version')
        if not name or not version:
            continue
        want = checksums.get(f'{name}@{version}')
        current = {}
        checksum_path = os.path.join(dest, '.cargo-checksum.json')
        if os.path.exists(checksum_path):
            try:
                current = json.load(open(checksum_path))
            except Exception:
                current = {}
        if want:
            if current.get('package') != want:
                write_checksum(dest, want)
                updated += 1
        elif 'package' in current:
            write_checksum(dest, None)
            updated += 1
    log(f'checksums synced ({updated} updated)')


def write_cargo_config():
    config_path = os.path.join(ROOT, '.cargo', 'config.toml')
    if os.path.exists(config_path):
        log('.cargo/config.toml: already present')
        return
    os.makedirs(os.path.dirname(config_path))
    with open(config_path, 'w') as handle:
        handle.write(f'''# Generated by tools/fetch-vendor.py — do not edit; see horizon/README.md.
[source.crates-io]
replace-with = "vendored-sources"

[source.vendored-sources]
directory = "{VENDOR}"
''')
    log('.cargo/config.toml: written')


def main():
    os.makedirs(STAGE, exist_ok=True)
    os.makedirs(VENDOR, exist_ok=True)
    failures = []
    for name, spec in sorted(F.items()):
        expected = EXPECTED.get(name)
        if expected is None:
            log(f'  {name}: no expected version recorded; using 0.0.0 for tag guessing')
            expected = '0.0.0'
        status, _ = vendor_one(name, spec, expected)
        log(f'{name}: {status}')
        if status == 'FAILED':
            failures.append(name)
    if not fetch_syn():
        failures.append('syn')
    if not fetch_generic_array():
        failures.append('generic-array')
    # std (-Zbuild-std) closure
    failures += fetch_std()
    write_cargo_config()
    # Checksums consistent with both lockfiles
    sync_all_checksums()
    # Shims
    for name in SHIMS:
        dest = os.path.join(VENDOR, name)
        if os.path.exists(dest):
            log(f'{name}: cached')
            continue
        src = os.path.join(ROOT, 'tools', 'shims', name)
        if name == 'unicode_names2':
            os.makedirs(os.path.join(src, 'src'), exist_ok=True)
            subprocess.check_call([sys.executable, os.path.join(src, 'generate.py'), os.path.join(src, 'src', 'lib.rs')])
        shutil.copytree(src, dest, ignore=shutil.ignore_patterns('generate.py'))
        log(f'{name}: shim installed')
    shutil.rmtree(STAGE, ignore_errors=True)
    if failures:
        log('FAILED: ' + ', '.join(failures))
        sys.exit(1)
    log('vendor staging complete')

if __name__ == '__main__':
    main()

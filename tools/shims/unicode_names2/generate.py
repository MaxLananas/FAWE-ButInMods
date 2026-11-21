#!/usr/bin/env python3
"""Generate src/lib.rs for the vendored unicode_names2 shim.

Horizon's core crate uses `unicode_names2::character` and
`unicode_names2::name` to implement Java's `Character.codePointOf` /
`codePointName` string-keyed escapes for SNBT. The real crate is ~140k
Unicode name records; this shim rebuilds the same tables from Python's
`unicodedata` (Unicode 15 names; the algorithmic CJK/Hangul ranges and the
control aliases are handled on the Horizon side, not here).
"""
import sys
import unicodedata

OUT = sys.argv[1]

entries = []  # (name, cp)
for cp in range(0x110000):
    try:
        name = unicodedata.name(chr(cp))
    except ValueError:
        continue
    assert name.isascii(), (name, cp)
    entries.append((name, cp))

by_name = sorted(entries, key=lambda e: e[0])
by_cp = sorted(entries, key=lambda e: e[1])

def esc(s):
    return s.replace('\\', '\\\\').replace('"', '\\"')

with open(OUT, 'w') as f:
    f.write('//! Vendored shim of `unicode_names2` 2.0.0: the two functions Horizon\'s core crate uses.\n')
    f.write('//! Generated from Python `unicodedata`; do not edit by hand (see tools/shims/unicode_names2/generate.py).\n')
    f.write('static BY_NAME: &[(&str, char)] = &[\n')
    for name, cp in by_name:
        f.write(f'    ("{esc(name)}", \'\\u{{{cp:x}}}\'),\n')
    f.write('];\n\nstatic BY_CP: &[(u32, &str)] = &[\n')
    for name, cp in by_cp:
        f.write(f'    ({cp}, "{esc(name)}"),\n')
    f.write('''];

/// Java-ish name → code point. Case-insensitive over ASCII, like the caller expects.
pub fn character(name: &str) -> Option<char> {
    let mut upper = [0u8; 64];
    let bytes = name.as_bytes();
    if bytes.len() > upper.len() {
        return None;
    }
    for (slot, byte) in upper.iter_mut().zip(bytes) {
        *slot = byte.to_ascii_uppercase();
    }
    let needle = std::str::from_utf8(&upper[..bytes.len()]).ok()?;
    BY_NAME
        .binary_search_by(|(key, _)| key.as_bytes().cmp(needle.as_bytes()))
        .ok()
        .map(|index| BY_NAME[index].1)
}

/// Canonical (uppercase) name(s) of a character, as an iterator of string slices.
pub fn name(character: char) -> Option<NameIter> {
    BY_CP
        .binary_search_by_key(&(character as u32), |(cp, _)| *cp)
        .ok()
        .map(|index| NameIter { index })
}

pub struct NameIter {
    index: usize,
}

impl Iterator for NameIter {
    type Item = &'static str;
    fn next(&mut self) -> Option<Self::Item> {
        if self.index == usize::MAX {
            return None;
        }
        let value = BY_CP[self.index].1;
        self.index = usize::MAX;
        Some(value)
    }
}
''')
print(f'entries: {len(entries)}', file=sys.stderr)

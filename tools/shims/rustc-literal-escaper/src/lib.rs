//! Escape analysis for Rust's literals: the unescapers `proc_macro` (and the parser)
//! use to turn a token's text into its value.
//!
//! `no_std`: with `rustc-dep-of-std` the crate builds against the sysroot shims.
#![no_std]

extern crate alloc;

use alloc::string::String;
use core::fmt;
use core::ops::Range;

/// Something wrong with an escape sequence, or with the literal around it.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum EscapeError {
    /// Expected 1 char, but 0 were found.
    ZeroChars,
    /// Expected 1 char, but more than 1 were found.
    MoreThanOneChar,
    /// Escaped '\' character without continuation.
    LoneSlash,
    /// Invalid escape character (e.g. '\z').
    InvalidEscape,
    /// Raw '\r' encountered.
    BareCarriageReturn,
    /// Raw '\r' encountered in raw string.
    BareCarriageReturnInRawString,
    /// Unescaped character that was expected to be escaped (e.g. raw '\t').
    EscapeOnlyChar,
    /// Numeric character escape is too short (e.g. '\x1').
    TooShortHexEscape,
    /// Invalid character in numeric escape (e.g. '\xGG').
    InvalidCharInHexEscape,
    /// Numeric character escape is out of range (e.g. '\xFF' in a `str`).
    OutOfRangeHexEscape,
    /// No brace in unicode escape.
    NoBraceInUnicodeEscape,
    /// Invalid character in unicode escape (e.g. '\u{Z}').
    InvalidCharInUnicodeEscape,
    /// Empty unicode escape (`\u{}`).
    EmptyUnicodeEscape,
    /// Unclosed unicode escape (`\u{41`).
    UnclosedUnicodeEscape,
    /// Underscore at the start of the unicode escape.
    LeadingUnderscoreUnicodeEscape,
    /// Too many digits in the unicode escape.
    OverlongUnicodeEscape,
    /// Lone surrogate in unicode escape.
    LoneSurrogateUnicodeEscape,
    /// Code point out of range in unicode escape (`\u{110000}`).
    OutOfRangeUnicodeEscape,
    /// Unicode escape inside a byte literal.
    UnicodeEscapeInByte,
    /// Non-ASCII character inside a byte literal.
    NonAsciiCharInByte,
    /// NUL character inside a c-string literal.
    NulInCStr,
    /// Non-fatal: whitespace after a line-continuation backslash before the newline.
    UnskippedWhitespaceWarning,
    /// Non-fatal: several line-continuation backslashes in a row.
    MultipleSkippedLinesWarning,
}

impl EscapeError {
    /// Warnings don't stop the unescape; every other error does.
    pub fn is_fatal(&self) -> bool {
        !matches!(
            self,
            EscapeError::UnskippedWhitespaceWarning | EscapeError::MultipleSkippedLinesWarning
        )
    }
}

impl fmt::Display for EscapeError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        let text = match self {
            EscapeError::ZeroChars => "expected 1 char, but 0 were found",
            EscapeError::MoreThanOneChar => "expected 1 char, but more than 1 were found",
            EscapeError::LoneSlash => "escaped `\\` character without continuation",
            EscapeError::InvalidEscape => "invalid escape character",
            EscapeError::BareCarriageReturn => "raw `\\r` encountered",
            EscapeError::BareCarriageReturnInRawString => "raw `\\r` encountered in raw string",
            EscapeError::EscapeOnlyChar => "character must be escaped",
            EscapeError::TooShortHexEscape => "numeric character escape is too short",
            EscapeError::InvalidCharInHexEscape => "invalid character in numeric escape",
            EscapeError::OutOfRangeHexEscape => "numeric character escape is out of range",
            EscapeError::NoBraceInUnicodeEscape => "no brace in unicode escape",
            EscapeError::InvalidCharInUnicodeEscape => "invalid character in unicode escape",
            EscapeError::EmptyUnicodeEscape => "empty unicode escape",
            EscapeError::UnclosedUnicodeEscape => "unclosed unicode escape",
            EscapeError::LeadingUnderscoreUnicodeEscape => "leading underscore in unicode escape",
            EscapeError::OverlongUnicodeEscape => "overlong unicode escape",
            EscapeError::LoneSurrogateUnicodeEscape => "lone surrogate in unicode escape",
            EscapeError::OutOfRangeUnicodeEscape => "unicode escape out of range",
            EscapeError::UnicodeEscapeInByte => "unicode escape in a byte literal",
            EscapeError::NonAsciiCharInByte => "non-ASCII character in a byte literal",
            EscapeError::NulInCStr => "NUL character in a c-string literal",
            EscapeError::UnskippedWhitespaceWarning => {
                "whitespace after a line-continuation backslash"
            }
            EscapeError::MultipleSkippedLinesWarning => {
                "several line-continuation backslashes in a row"
            }
        };
        f.write_str(text)
    }
}

/// What an unescape emits for a c-string: plain chars, or single bytes >= 0x80.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum MixedUnit {
    /// A full unicode scalar value (or ASCII byte), from raw text or `\u{..}`.
    Char(Char),
    /// A byte outside the ASCII range, from a `\xHH` escape.
    HighByte(HighByte),
}

/// A `char` newtype (the zero case is reported as `NulInCStr` in c-strings).
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub struct Char(char);

impl Char {
    /// The wrapped character.
    pub fn get(self) -> char {
        self.0
    }
}

/// A non-zero byte newtype.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub struct HighByte(u8);

impl HighByte {
    /// The wrapped byte (never zero when constructed by this crate).
    pub fn get(self) -> u8 {
        self.0
    }
}

impl From<core::num::NonZeroU8> for HighByte {
    fn from(value: core::num::NonZeroU8) -> Self {
        HighByte(value.get())
    }
}

/// Which kind of literal is being unescaped.
#[derive(Clone, Copy, PartialEq, Eq)]
enum Mode {
    /// `'\n'`, a `char` literal: exactly one scalar.
    Char,
    /// `b'a'`, a byte character literal: exactly one byte.
    Byte,
    /// `"..."`, a string literal.
    Str,
    /// `b"..."`, a byte string literal.
    ByteStr,
    /// `c"..."`, a c-string literal.
    CStr,
}

/// One decoded unit out of the input.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
enum Unit {
    /// A unicode scalar value.
    Scalar(u32),
    /// A raw byte from a `\xHH` escape.
    Byte(u8),
}

fn is_hex(byte: u8) -> bool {
    byte.is_ascii_hexdigit()
}

fn hex_value(byte: u8) -> u32 {
    match byte {
        b'0'..=b'9' => (byte - b'0') as u32,
        b'a'..=b'f' => (byte - b'a' + 10) as u32,
        b'A'..=b'F' => (byte - b'A' + 10) as u32,
        _ => 0,
    }
}

fn utf8_len(first: u8) -> usize {
    match first {
        0x00..=0x7F => 1,
        0xC0..=0xDF => 2,
        0xE0..=0xEF => 3,
        _ => 4,
    }
}

fn span(start: usize, end: usize) -> Range<usize> {
    start..end
}

struct Context<'a> {
    mode: Mode,
    input: &'a str,
}

impl<'a> Context<'a> {
    fn run<F>(&self, mut emit: F)
    where
        F: FnMut(Range<usize>, Result<Unit, EscapeError>),
    {
        let bytes = self.input.as_bytes();
        let mut i = 0;
        'outer: while i < bytes.len() {
            let start = i;
            let byte = bytes[i];
            if byte == b'\\' {
                i += 1;
                if i >= bytes.len() {
                    emit(span(start, i), Err(EscapeError::LoneSlash));
                    return;
                }
                match bytes[i] {
                    b'\\' => {
                        emit(span(start, i + 1), Ok(Unit::Scalar('\\' as u32)));
                        i += 1;
                    }
                    b'\'' => {
                        emit(span(start, i + 1), Ok(Unit::Scalar('\'' as u32)));
                        i += 1;
                    }
                    b'"' => {
                        emit(span(start, i + 1), Ok(Unit::Scalar('"' as u32)));
                        i += 1;
                    }
                    b'n' => {
                        emit(span(start, i + 1), Ok(Unit::Scalar('\n' as u32)));
                        i += 1;
                    }
                    b'r' => {
                        emit(span(start, i + 1), Ok(Unit::Scalar('\r' as u32)));
                        i += 1;
                    }
                    b't' => {
                        emit(span(start, i + 1), Ok(Unit::Scalar('\t' as u32)));
                        i += 1;
                    }
                    b'0' => {
                        emit(span(start, i + 1), Ok(Unit::Scalar(0)));
                        i += 1;
                    }
                    b'x' => {
                        i += 1;
                        match self.hex_escape(i, 2) {
                            Ok((value, next)) => {
                                let allowed = match self.mode {
                                    Mode::Str | Mode::Char => value <= 0x7F,
                                    Mode::CStr => true,
                                    Mode::Byte | Mode::ByteStr => true,
                                };
                                if !allowed {
                                    emit(span(start, next), Err(EscapeError::OutOfRangeHexEscape));
                                    return;
                                }
                                emit(span(start, next), Ok(Unit::Byte(value as u8)));
                                i = next;
                            }
                            Err(err) => {
                                emit(span(start, (i + 2).min(bytes.len())), Err(err));
                                return;
                            }
                        }
                    }
                    b'u' => {
                        i += 1;
                        if i >= bytes.len() || bytes[i] != b'{' {
                            emit(span(start, i), Err(EscapeError::NoBraceInUnicodeEscape));
                            return;
                        }
                        i += 1;
                        let mut value: u32 = 0;
                        let mut digits = 0u32;
                        while i < bytes.len() && is_hex(bytes[i]) {
                            value = value * 16 + hex_value(bytes[i]);
                            digits += 1;
                            i += 1;
                            if digits > 6 {
                                emit(span(start, i), Err(EscapeError::OverlongUnicodeEscape));
                                return;
                            }
                        }
                        if digits == 0 {
                            let err = if i < bytes.len() && bytes[i] == b'}' {
                                EscapeError::EmptyUnicodeEscape
                            } else if i < bytes.len() {
                                EscapeError::InvalidCharInUnicodeEscape
                            } else {
                                EscapeError::UnclosedUnicodeEscape
                            };
                            emit(span(start, i), Err(err));
                            return;
                        }
                        if i >= bytes.len() || bytes[i] != b'}' {
                            let err = if i >= bytes.len() {
                                EscapeError::UnclosedUnicodeEscape
                            } else {
                                EscapeError::InvalidCharInUnicodeEscape
                            };
                            emit(span(start, i), Err(err));
                            return;
                        }
                        i += 1;
                        if self.mode == Mode::Byte || self.mode == Mode::ByteStr {
                            emit(span(start, i), Err(EscapeError::UnicodeEscapeInByte));
                            return;
                        }
                        if value > 0x10_FFFF {
                            emit(span(start, i), Err(EscapeError::OutOfRangeUnicodeEscape));
                            return;
                        }
                        if (0xD800..=0xDFFF).contains(&value) {
                            emit(span(start, i), Err(EscapeError::LoneSurrogateUnicodeEscape));
                            return;
                        }
                        emit(span(start, i), Ok(Unit::Scalar(value)));
                    }
                    b'\n' | b'\r' => {
                        match self.skip_continuation(i, &mut emit, start) {
                            Some(next) => {
                                i = next;
                                continue 'outer;
                            }
                            None => return,
                        }
                    }
                    _ => {
                        emit(span(start, i + 1), Err(EscapeError::InvalidEscape));
                        return;
                    }
                }
            } else {
                match byte {
                    b'\r' => {
                        emit(span(start, i + 1), Err(EscapeError::BareCarriageReturn));
                        return;
                    }
                    _ => {
                        let ch_len = utf8_len(byte);
                        let end = (i + ch_len).min(bytes.len());
                        match self.input.get(i..end).and_then(|raw| raw.chars().next()) {
                            Some(c) => {
                                if (self.mode == Mode::Byte || self.mode == Mode::ByteStr)
                                    && !c.is_ascii()
                                {
                                    emit(span(start, end), Err(EscapeError::NonAsciiCharInByte));
                                    return;
                                }
                                emit(span(start, end), Ok(Unit::Scalar(c as u32)));
                                i = end;
                            }
                            None => {
                                emit(span(start, end), Err(EscapeError::InvalidEscape));
                                return;
                            }
                        }
                    }
                }
            }
        }
    }

    fn hex_escape(&self, start: usize, needed: usize) -> Result<(u32, usize), EscapeError> {
        let bytes = self.input.as_bytes();
        let mut value: u32 = 0;
        let mut i = start;
        for _ in 0..needed {
            if i >= bytes.len() {
                return Err(EscapeError::TooShortHexEscape);
            }
            if !is_hex(bytes[i]) {
                return Err(EscapeError::InvalidCharInHexEscape);
            }
            value = value * 16 + hex_value(bytes[i]);
            i += 1;
        }
        Ok((value, i))
    }

    /// Skip a line continuation (`\\` + newline), the whitespace that may follow the
    /// newline and any further continuation backslashes. Non-fatal warnings are
    /// reported through `emit`. `from` must point at the newline following the backslash.
    fn skip_continuation(
        &self,
        from: usize,
        emit: &mut dyn FnMut(Range<usize>, Result<Unit, EscapeError>),
        _start: usize,
    ) -> Option<usize> {
        let bytes = self.input.as_bytes();
        let mut i = from;
        let mut backslash_count = 0u32;
        'outer: loop {
            // Whitespace between backslashes and newlines is skipped, warned.
            let ws_start = i;
            while matches!(bytes.get(i), Some(b' ') | Some(b'\t')) {
                i += 1;
            }
            if i != ws_start {
                emit(span(ws_start, i), Err(EscapeError::UnskippedWhitespaceWarning));
            }
            match bytes.get(i) {
                Some(b'\r') => {
                    i += 1;
                    if bytes.get(i) == Some(&b'\n') {
                        i += 1;
                    }
                }
                Some(b'\n') => {
                    i += 1;
                }
                _ => break 'outer,
            }
            // Leading whitespace on the continuation line is silently ignored.
            while matches!(bytes.get(i), Some(b' ') | Some(b'\t')) {
                i += 1;
            }
            // After the newline: another continuation backslash?
            if bytes.get(i) == Some(&b'\\') {
                backslash_count += 1;
                if backslash_count >= 2 {
                    emit(span(i, i + 1), Err(EscapeError::MultipleSkippedLinesWarning));
                }
                i += 1;
                continue 'outer;
            }
            break 'outer;
        }
        Some(i)
    }
}

/// A unit callback tag that tells the caller how to interpret the value.
enum UnitTag {
    Scalar(u32),
    Byte(u8),
}

fn single(
    input: &str,
    mode: Mode,
    convert: impl Fn(UnitTag) -> Option<u32>,
) -> Result<u32, EscapeError> {
    if input.is_empty() {
        return Err(EscapeError::ZeroChars);
    }
    let ctx = Context { mode, input };
    let mut units = 0usize;
    let mut result = None;
    let mut fatal = None;
    ctx.run(|_, unit| {
        if fatal.is_some() {
            return;
        }
        match unit {
            Ok(Unit::Scalar(value)) => {
                units += 1;
                result = convert(UnitTag::Scalar(value));
                if result.is_none() {
                    fatal = Some(EscapeError::OutOfRangeHexEscape);
                }
            }
            Ok(Unit::Byte(value)) => {
                units += 1;
                result = convert(UnitTag::Byte(value));
                if result.is_none() {
                    fatal = Some(EscapeError::OutOfRangeHexEscape);
                }
            }
            Err(err) => {
                if err.is_fatal() {
                    fatal = Some(err);
                }
            }
        }
    });
    if let Some(err) = fatal {
        return Err(err);
    }
    if units == 0 {
        return Err(EscapeError::ZeroChars);
    }
    if units > 1 {
        return Err(EscapeError::MoreThanOneChar);
    }
    result.ok_or(EscapeError::ZeroChars)
}

/// Unescape a `char` literal's text (the part between the quotes).
pub fn unescape_char(input: &str) -> Result<char, EscapeError> {
    single(input, Mode::Char, |unit| match unit {
        UnitTag::Scalar(value) => char::try_from(value).ok().map(|c| c as u32),
        UnitTag::Byte(value) => Some(value as u32),
    })
    .map(|value| char::try_from(value).expect("checked"))
}

/// Unescape a byte character literal's text (the part between the quotes).
pub fn unescape_byte(input: &str) -> Result<u8, EscapeError> {
    single(input, Mode::Byte, |unit| match unit {
        UnitTag::Scalar(value) => u8::try_from(value).ok().map(|b| b as u32),
        UnitTag::Byte(value) => Some(value as u32),
    })
    .map(|value| value as u8)
}

/// Unescape a string literal's text, calling `handle` for every character.
/// The span is the byte range of the unit in `input`.
pub fn unescape_str(input: &str, mut handle: impl FnMut(Range<usize>, Result<char, EscapeError>)) {
    let ctx = Context {
        mode: Mode::Str,
        input,
    };
    ctx.run(|range, unit| match unit {
        Ok(Unit::Scalar(value)) => match char::try_from(value) {
            Ok(c) => handle(range, Ok(c)),
            Err(_) => handle(range, Err(EscapeError::OutOfRangeHexEscape)),
        },
        Ok(Unit::Byte(value)) => handle(range, Ok(value as char)),
        Err(err) => handle(range, Err(err)),
    });
}

/// Unescape a byte string literal's text, calling `handle` for every byte.
pub fn unescape_byte_str(
    input: &str,
    mut handle: impl FnMut(Range<usize>, Result<u8, EscapeError>),
) {
    let ctx = Context {
        mode: Mode::ByteStr,
        input,
    };
    ctx.run(|range, unit| match unit {
        Ok(Unit::Scalar(value)) => match u8::try_from(value) {
            Ok(b) => handle(range, Ok(b)),
            Err(_) => handle(range, Err(EscapeError::OutOfRangeHexEscape)),
        },
        Ok(Unit::Byte(value)) => handle(range, Ok(value)),
        Err(err) => handle(range, Err(err)),
    });
}

/// Unescape a c-string literal's text, calling `handle` for every unit.
pub fn unescape_c_str(
    input: &str,
    mut handle: impl FnMut(Range<usize>, Result<MixedUnit, EscapeError>),
) {
    let ctx = Context {
        mode: Mode::CStr,
        input,
    };
    ctx.run(|range, unit| match unit {
        Ok(Unit::Scalar(0)) => handle(range, Err(EscapeError::NulInCStr)),
        Ok(Unit::Scalar(value)) => match char::try_from(value) {
            Ok(c) => handle(range, Ok(MixedUnit::Char(Char(c)))),
            Err(_) => handle(range, Err(EscapeError::OutOfRangeHexEscape)),
        },
        Ok(Unit::Byte(0)) => handle(range, Err(EscapeError::NulInCStr)),
        Ok(Unit::Byte(value)) if value < 0x80 => {
            handle(range, Ok(MixedUnit::Char(Char(value as char))))
        }
        Ok(Unit::Byte(value)) => {
            let nonzero = core::num::NonZeroU8::new(value).expect("checked non-zero");
            handle(range, Ok(MixedUnit::HighByte(HighByte(nonzero.get()))))
        }
        Err(err) => handle(range, Err(err)),
    });
}

/// The longest the literal's text may be, in bytes; provided for parity with the
/// upstream crate's generous limit. Any input is accepted.
pub const MAX_LITERAL_LEN: usize = 1024 * 1024;

/// Unescape an integer-like literal for tests and tools: identical semantics to
/// [`unescape_str`] but collects the scalar values into a `String`.
pub fn unescape_to_string(input: &str) -> Result<String, EscapeError> {
    let mut output = String::with_capacity(input.len());
    let mut fatal = None;
    unescape_str(input, |_, unit| {
        if fatal.is_some() {
            return;
        }
        match unit {
            Ok(c) => output.push(c),
            Err(err) => {
                if err.is_fatal() {
                    fatal = Some(err);
                }
            }
        }
    });
    match fatal {
        Some(err) => Err(err),
        None => Ok(output),
    }
}

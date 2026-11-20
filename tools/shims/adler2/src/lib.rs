//! The Adler-32 checksum (RFC 1950), `no_std`, with the small `Adler32` API
//! `miniz_oxide` uses.
#![no_std]

const BASE: u32 = 65521;
const NMAX: usize = 5552;

/// Running Adler-32 state.
#[derive(Clone, Copy, Debug)]
pub struct Adler32 {
    a: u32,
    b: u32,
}

impl Default for Adler32 {
    fn default() -> Self {
        Self::new()
    }
}

impl Adler32 {
    /// Start a fresh checksum.
    pub fn new() -> Self {
        Adler32 { a: 1, b: 0 }
    }

    /// Resume from a previously stored `checksum` value.
    pub fn from_checksum(checksum: u32) -> Self {
        Adler32 {
            a: checksum & 0xffff,
            b: checksum >> 16,
        }
    }

    /// Feed a byte slice into the checksum.
    pub fn write_slice(&mut self, data: &[u8]) {
        let mut a = self.a;
        let mut b = self.b;
        for chunk in data.chunks(NMAX) {
            for &byte in chunk {
                a += byte as u32;
                b += a;
            }
            a %= BASE;
            b %= BASE;
        }
        self.a = a;
        self.b = b;
    }

    /// Feed a single byte into the checksum.
    pub fn write(&mut self, byte: u8) {
        self.write_slice(core::slice::from_ref(&byte));
    }

    /// The current checksum value.
    pub fn checksum(&self) -> u32 {
        (self.b << 16) | self.a
    }
}

/// One-shot: the Adler-32 checksum of `data`.
pub fn adler32_slice(data: &[u8]) -> u32 {
    let mut adler = Adler32::new();
    adler.write_slice(data);
    adler.checksum()
}

/// The checksum of `data` with an initial value of `adler`.
pub fn adler32_slice_custom(adler: u32, data: &[u8]) -> u32 {
    let mut adler = Adler32::from_checksum(adler);
    adler.write_slice(data);
    adler.checksum()
}

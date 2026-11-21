//! FAWE pattern parser and position-dependent block producers.
//!
//! This module deliberately keeps patterns independent from a live host: block
//! names are resolved once through `BlockStateRegistry`, then millions of calls
//! to `apply` are allocation-free.

use std::sync::Arc;
use crate::expression::{Expression, Variables};
use crate::noise::{NoiseFn, Perlin, RidgedMultiFractal, Simplex, Voronoi};
use crate::registry::BlockStateRegistry;

#[derive(Clone, Debug)]
pub enum Pattern {
    Single(i32),
    Weighted(Vec<(f64, Pattern)>),
    Checker { size: i32, choices: Vec<Pattern> },
    Noise { scale: f64, kind: NoiseKind, choices: Vec<(f64, Pattern)> },
    Gradient { axis: u8, min: f64, max: f64, choices: Vec<(f64, Pattern)> },
    Expression { expression: Arc<Expression>, choices: Vec<(f64, Pattern)> },
    Offset { inner: Box<Pattern>, x: i32, y: i32, z: i32 },
    NoAxis { inner: Box<Pattern>, axis: u8 },
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum NoiseKind { Perlin, Simplex, Voronoi, Ridged }

impl Pattern {
    pub fn parse(registry: &dyn BlockStateRegistry, text: &str) -> Result<Self, String> {
        parse_pattern(registry, text.trim(), 0)
    }

    pub fn apply(&self, x: i32, y: i32, z: i32) -> i32 {
        match self {
            Self::Single(state) => *state,
            Self::Weighted(entries) => choose(entries, unit_hash(x, y, z), x, y, z),
            Self::Checker { size, choices } => {
                let size = (*size).max(1);
                let index = ((x.div_euclid(size) + y.div_euclid(size) + z.div_euclid(size))
                    .rem_euclid(choices.len() as i32)) as usize;
                choices[index].apply(x, y, z)
            }
            Self::Noise { scale, kind, choices } => {
                let (fx, fy, fz) = (x as f64 * scale, y as f64 * scale, z as f64 * scale);
                let value = match kind {
                    NoiseKind::Perlin => Perlin::new(0).unit(fx, fy, fz),
                    NoiseKind::Simplex => Simplex::new(0).unit(fx, fy, fz),
                    NoiseKind::Voronoi => Voronoi::new(0).unit(fx, fy, fz),
                    NoiseKind::Ridged => RidgedMultiFractal::new(0, 6, 2.0, 2.0).unit(fx, fy, fz),
                }.clamp(0.0, 1.0 - f64::EPSILON);
                choose(choices, value, x, y, z)
            }
            Self::Gradient { axis, min, max, choices } => {
                let coordinate = match axis { 0 => x as f64, 1 => y as f64, _ => z as f64 };
                let value = if (max - min).abs() < f64::EPSILON { 0.0 } else { ((coordinate - min) / (max - min)).clamp(0.0, 1.0 - f64::EPSILON) };
                choose(choices, value, x, y, z)
            }
            Self::Expression { expression, choices } => {
                let mut vars = Variables::new().set("x", x as f64).set("y", y as f64).set("z", z as f64);
                let value = expression.evaluate(&mut vars).unwrap_or(0.0).clamp(0.0, 1.0 - f64::EPSILON);
                choose(choices, value, x, y, z)
            }
            Self::Offset { inner, x: ox, y: oy, z: oz } => inner.apply(x + ox, y + oy, z + oz),
            Self::NoAxis { inner, axis } => match axis {
                0 => inner.apply(0, y, z), 1 => inner.apply(x, 0, z), _ => inner.apply(x, y, 0),
            },
        }
    }

    pub fn is_deterministic(&self) -> bool { true }
}

fn choose(entries: &[(f64, Pattern)], value: f64, x: i32, y: i32, z: i32) -> i32 {
    let total: f64 = entries.iter().map(|(w, _)| w.max(0.0)).sum();
    if total <= 0.0 { return 0; }
    let mut needle = value * total;
    for (weight, pattern) in entries {
        needle -= weight.max(0.0);
        if needle < 0.0 { return pattern.apply(x, y, z); }
    }
    entries.last().map_or(0, |(_, p)| p.apply(x, y, z))
}

fn unit_hash(x: i32, y: i32, z: i32) -> f64 {
    let mut n = (x as u32 as u64).wrapping_mul(0x9E37_79B1_85EB_CA87)
        ^ (y as u32 as u64).wrapping_mul(0xC2B2_AE3D_27D4_EB4F)
        ^ (z as u32 as u64).wrapping_mul(0x1656_67B1_9E37_79F9);
    n ^= n >> 30; n = n.wrapping_mul(0xBF58_476D_1CE4_E5B9);
    n ^= n >> 27; n = n.wrapping_mul(0x94D0_49BB_1331_11EB); n ^= n >> 31;
    (n >> 11) as f64 * (1.0 / ((1u64 << 53) as f64))
}

fn parse_pattern(reg: &dyn BlockStateRegistry, text: &str, depth: usize) -> Result<Pattern, String> {
    if depth > 32 { return Err("pattern nesting exceeds 32 levels".into()); }
    if text.is_empty() { return Err("empty pattern".into()); }
    let comma = split_top(text, ',');
    if comma.len() > 1 {
        let mut entries = Vec::new();
        for item in comma {
            let (weight, body) = parse_weight(item)?;
            entries.push((weight, parse_pattern(reg, body, depth + 1)?));
        }
        return Ok(Pattern::Weighted(entries));
    }
    if let Some(rest) = text.strip_prefix("#checker") {
        let args = brackets(rest)?;
        if args.is_empty() { return Err("#checker requires a pattern list".into()); }
        let choices = split_top(args[0], ',').into_iter().map(|p| parse_pattern(reg, p, depth + 1)).collect::<Result<Vec<_>, _>>()?;
        let size = match args.get(1) { Some(v) => v.parse::<i32>().map_err(|_| "invalid checker size".to_string())?, None => 1 };
        return Ok(Pattern::Checker { size: size.max(1), choices });
    }
    if let Some(rest) = text.strip_prefix("#gradient") {
        let args = brackets(rest)?;
        if args.len() != 4 { return Err("#gradient requires [axis][min][max][patterns]".into()); }
        let axis = match args[0].trim().to_ascii_lowercase().as_str() { "x" => 0, "y" => 1, "z" => 2, _ => return Err("gradient axis must be x, y, or z".into()) };
        let min = args[1].trim().parse::<f64>().map_err(|_| "invalid gradient minimum".to_string())?;
        let max = args[2].trim().parse::<f64>().map_err(|_| "invalid gradient maximum".to_string())?;
        let mut choices = Vec::new();
        for item in split_top(args[3], ',') { let (weight, body) = parse_weight(item)?; choices.push((weight, parse_pattern(reg, body, depth + 1)?)); }
        if choices.is_empty() { return Err("gradient needs at least one pattern".into()); }
        return Ok(Pattern::Gradient { axis, min, max, choices });
    }
    if let Some(rest) = text.strip_prefix("#expression") {
        let args = brackets(rest)?;
        if args.len() != 2 { return Err("#expression requires [expression][patterns]".into()); }
        let expression = Arc::new(Expression::compile(args[0]).map_err(|e| e.0)?);
        let mut choices = Vec::new();
        for item in split_top(args[1], ',') { let (weight, body) = parse_weight(item)?; choices.push((weight, parse_pattern(reg, body, depth + 1)?)); }
        if choices.is_empty() { return Err("expression pattern needs choices".into()); }
        return Ok(Pattern::Expression { expression, choices });
    }
    for (prefix, kind) in [("#perlin", NoiseKind::Perlin), ("#simplex", NoiseKind::Simplex), ("#voronoi", NoiseKind::Voronoi), ("#ridged", NoiseKind::Ridged)] {
        if let Some(rest) = text.strip_prefix(prefix) {
            let args = brackets(rest)?;
            if args.len() < 2 { return Err(format!("{prefix} requires [scale][patterns]")); }
            let scale = args[0].parse::<f64>().map_err(|_| "invalid noise scale".to_string())?;
            let mut choices = Vec::new();
            for item in split_top(args[1], ',') {
                let (weight, body) = parse_weight(item)?;
                choices.push((weight, parse_pattern(reg, body, depth + 1)?));
            }
            return Ok(Pattern::Noise { scale, kind, choices });
        }
    }
    for (prefix, axis) in [("#nx", 0), ("#ny", 1), ("#nz", 2)] {
        if let Some(rest) = text.strip_prefix(prefix) {
            let args = brackets(rest)?;
            let body = args.first().ok_or_else(|| format!("{prefix} requires [pattern]"))?;
            return Ok(Pattern::NoAxis { inner: Box::new(parse_pattern(reg, body, depth + 1)?), axis });
        }
    }
    if let Some(rest) = text.strip_prefix("#offset") {
        let args = brackets(rest)?;
        if args.len() != 4 { return Err("#offset requires [pattern][x][y][z]".into()); }
        let number = |s: &&str| s.parse::<i32>().map_err(|_| "invalid offset".to_string());
        return Ok(Pattern::Offset { inner: Box::new(parse_pattern(reg, args[0], depth + 1)?), x: number(&args[1])?, y: number(&args[2])?, z: number(&args[3])? });
    }
    let state = reg.parse(text);
    if state < 0 { Err(format!("unknown block or pattern '{text}'")) } else { Ok(Pattern::Single(state)) }
}

fn parse_weight(item: &str) -> Result<(f64, &str), String> {
    if let Some(pos) = item.find('%') {
        let weight = item[..pos].trim().parse::<f64>().map_err(|_| format!("invalid weight in '{item}'"))?;
        if weight < 0.0 { return Err("pattern weights cannot be negative".into()); }
        Ok((weight, item[pos + 1..].trim()))
    } else { Ok((1.0, item.trim())) }
}

fn split_top(text: &str, delimiter: char) -> Vec<&str> {
    let mut depth = 0i32; let mut start = 0; let mut out = Vec::new();
    for (i, ch) in text.char_indices() {
        match ch { '[' => depth += 1, ']' => depth -= 1, _ => {} }
        if ch == delimiter && depth == 0 { out.push(text[start..i].trim()); start = i + ch.len_utf8(); }
    }
    out.push(text[start..].trim()); out
}

fn brackets(mut text: &str) -> Result<Vec<&str>, String> {
    let mut out = Vec::new();
    while !text.trim().is_empty() {
        text = text.trim();
        if !text.starts_with('[') { return Err(format!("expected '[', found '{text}'")); }
        let mut depth = 0; let mut end = None;
        for (i, ch) in text.char_indices() {
            if ch == '[' { depth += 1; } else if ch == ']' { depth -= 1; if depth == 0 { end = Some(i); break; } }
        }
        let end = end.ok_or_else(|| "unclosed pattern bracket".to_string())?;
        out.push(&text[1..end]); text = &text[end + 1..];
    }
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::registry::InMemoryBlockRegistry;
    #[test] fn parses_single_weighted_checker_noise_and_wrappers() {
        let reg = InMemoryBlockRegistry::with_vanilla_subset();
        let stone = reg.parse("stone"); let dirt = reg.parse("dirt");
        assert_eq!(Pattern::parse(&reg, "stone").unwrap().apply(1,2,3), stone);
        let weighted = Pattern::parse(&reg, "25%stone,75%dirt").unwrap();
        assert!((0..50).any(|x| weighted.apply(x, 0, 0) == stone));
        assert!((0..50).any(|x| weighted.apply(x, 0, 0) == dirt));
        let checker = Pattern::parse(&reg, "#checker[stone,dirt][2]").unwrap();
        assert_eq!(checker.apply(0,0,0), stone); assert_eq!(checker.apply(2,0,0), dirt);
        assert!(Pattern::parse(&reg, "#perlin[0.1][stone,dirt]").is_ok());
        assert!(Pattern::parse(&reg, "#nx[#checker[stone,dirt]]").is_ok());
        let gradient = Pattern::parse(&reg, "#gradient[x][0][10][stone,dirt]").unwrap();
        assert_eq!(gradient.apply(0,0,0), stone); assert_eq!(gradient.apply(10,0,0), dirt);
        let expression = Pattern::parse(&reg, "#expression[x/10][stone,dirt]").unwrap();
        assert_eq!(expression.apply(0,0,0), stone); assert_eq!(expression.apply(10,0,0), dirt);
    }
    #[test] fn rejects_invalid_patterns() {
        let reg = InMemoryBlockRegistry::with_vanilla_subset();
        assert!(Pattern::parse(&reg, "no_such_block").is_err());
        assert!(Pattern::parse(&reg, "#offset[stone][x][0][0]").is_err());
        assert!(Pattern::parse(&reg, "#checker[stone,dirt").is_err());
    }
}

//! Composable FAWE masks used by replace operations and brushes.

use crate::registry::BlockStateRegistry;
use crate::world::Extent;

#[derive(Clone, Debug)]
pub enum Mask {
    Any,
    None,
    Blocks(Vec<i32>),
    Category(String),
    Air,
    Existing,
    Solid,
    Liquid,
    Height { min: i32, max: i32 },
    Not(Box<Mask>),
    All(Vec<Mask>),
    AnyOf(Vec<Mask>),
}

impl Mask {
    pub fn parse(registry: &dyn BlockStateRegistry, text: &str) -> Result<Self, String> {
        parse_mask(registry, text.trim(), 0)
    }

    pub fn test<E: Extent + ?Sized>(&self, extent: &E, registry: &dyn BlockStateRegistry, x: i32, y: i32, z: i32) -> bool {
        match self {
            Self::Any => true,
            Self::None => false,
            Self::Blocks(states) => states.contains(&extent.get_block(x, y, z)),
            Self::Category(name) => registry.matches_category(extent.get_block(x, y, z), name),
            Self::Air => registry.is_air_like(extent.get_block(x, y, z)),
            Self::Existing => !registry.is_air_like(extent.get_block(x, y, z)),
            Self::Solid => registry.is_solid(extent.get_block(x, y, z)),
            Self::Liquid => registry.is_liquid(extent.get_block(x, y, z)),
            Self::Height { min, max } => y >= *min && y <= *max,
            Self::Not(inner) => !inner.test(extent, registry, x, y, z),
            Self::All(parts) => parts.iter().all(|m| m.test(extent, registry, x, y, z)),
            Self::AnyOf(parts) => parts.iter().any(|m| m.test(extent, registry, x, y, z)),
        }
    }
}

fn parse_mask(reg: &dyn BlockStateRegistry, text: &str, depth: usize) -> Result<Mask, String> {
    if depth > 32 { return Err("mask nesting exceeds 32 levels".into()); }
    let text = strip_outer(text.trim());
    if text.is_empty() { return Err("empty mask".into()); }
    let parts = split_top(text, '|');
    if parts.len() > 1 { return Ok(Mask::AnyOf(parts.into_iter().map(|p| parse_mask(reg, p, depth + 1)).collect::<Result<_, _>>()?)); }
    let parts = split_top(text, '&');
    if parts.len() > 1 { return Ok(Mask::All(parts.into_iter().map(|p| parse_mask(reg, p, depth + 1)).collect::<Result<_, _>>()?)); }
    if let Some(rest) = text.strip_prefix('!') { return Ok(Mask::Not(Box::new(parse_mask(reg, rest, depth + 1)?))); }
    match text.to_ascii_lowercase().as_str() {
        "*" | "#any" => return Ok(Mask::Any),
        "#none" => return Ok(Mask::None),
        "#air" => return Ok(Mask::Air),
        "#existing" | "#notair" => return Ok(Mask::Existing),
        "#solid" => return Ok(Mask::Solid),
        "#liquid" => return Ok(Mask::Liquid),
        _ => {}
    }
    if let Some(body) = text.strip_prefix("#y[").and_then(|s| s.strip_suffix(']')) {
        let (a, b) = body.split_once(',').or_else(|| body.split_once(".."))
            .ok_or_else(|| "#y requires [min,max]".to_string())?;
        let min: i32 = a.trim().parse().map_err(|_| "invalid minimum Y".to_string())?;
        let max: i32 = b.trim().parse().map_err(|_| "invalid maximum Y".to_string())?;
        return Ok(Mask::Height { min: min.min(max), max: min.max(max) });
    }
    if let Some(category) = text.strip_prefix("##") {
        return Ok(Mask::Category(category.to_ascii_lowercase()));
    }
    let mut states = Vec::new();
    for part in split_top(text, ',') {
        let state = reg.parse(part);
        if state < 0 { return Err(format!("unknown block or mask '{part}'")); }
        states.push(state);
    }
    Ok(Mask::Blocks(states))
}

fn strip_outer(mut text: &str) -> &str {
    loop {
        if !(text.starts_with('(') && text.ends_with(')')) { return text; }
        let mut depth = 0;
        let mut wraps = true;
        for (i, c) in text.char_indices() {
            if c == '(' { depth += 1; } else if c == ')' { depth -= 1; }
            if depth == 0 && i + c.len_utf8() < text.len() { wraps = false; break; }
        }
        if !wraps { return text; }
        text = text[1..text.len()-1].trim();
    }
}

fn split_top(text: &str, delimiter: char) -> Vec<&str> {
    let mut depth = 0i32; let mut start = 0; let mut out = Vec::new();
    for (i, c) in text.char_indices() {
        match c { '(' | '[' => depth += 1, ')' | ']' => depth -= 1, _ => {} }
        if c == delimiter && depth == 0 { out.push(text[start..i].trim()); start = i + 1; }
    }
    out.push(text[start..].trim()); out
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::registry::InMemoryBlockRegistry;
    use crate::world::{Extent, InMemoryWorld};
    #[test] fn boolean_block_height_and_material_masks_work() {
        let reg = InMemoryBlockRegistry::with_vanilla_subset();
        let mut world = InMemoryWorld::default();
        let stone = reg.parse("stone"); let dirt = reg.parse("dirt");
        world.set_block(0, 64, 0, stone); world.set_block(1, 64, 0, dirt);
        let mask = Mask::parse(&reg, "(stone|dirt)&#y[60,70]").unwrap();
        assert!(mask.test(&world, &reg, 0,64,0)); assert!(!mask.test(&world, &reg, 0,80,0));
        assert!(Mask::parse(&reg, "#existing").unwrap().test(&world, &reg, 1,64,0));
        assert!(Mask::parse(&reg, "!#existing").unwrap().test(&world, &reg, 2,64,0));
    }
    #[test] fn malformed_masks_are_rejected() {
        let reg = InMemoryBlockRegistry::with_vanilla_subset();
        assert!(Mask::parse(&reg, "#y[x,4]").is_err());
        assert!(Mask::parse(&reg, "definitely_missing").is_err());
    }
}

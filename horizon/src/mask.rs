//! Composable FAWE masks used by replace operations and brushes.

use std::sync::Arc;
use crate::expression::{Expression, Variables};
use crate::registry::BlockStateRegistry;
use crate::noise::{NoiseFn, Perlin, Simplex, Voronoi, RidgedMultiFractal};
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
    Sphere { x: f64, y: f64, z: f64, radius2: f64 },
    Cylinder { x: f64, z: f64, radius2: f64 },
    Grid { x: i32, y: i32, z: i32 },
    Surface,
    Chance(f64),
    Noise { kind: u8, scale: f64, threshold: f64 },
    Expression(Arc<Expression>),
    Offset { inner: Box<Mask>, x: i32, y: i32, z: i32 },
    Adjacent { inner: Box<Mask>, min: usize, max: usize },
    Nearby { inner: Box<Mask>, radius: i32, min: usize, max: usize },
    Exposed { min: usize, max: usize },
    DirectionalExposure { x: i32, y: i32, z: i32 },
    Scale { inner: Box<Mask>, x: f64, y: f64, z: f64 },
    RotateY { inner: Box<Mask>, sin: f64, cos: f64 },
    Mirror { inner: Box<Mask>, axis: u8 },
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
            Self::Sphere { x: cx, y: cy, z: cz, radius2 } => (x as f64-cx).powi(2)+(y as f64-cy).powi(2)+(z as f64-cz).powi(2)<=*radius2,
            Self::Cylinder { x: cx, z: cz, radius2 } => (x as f64-cx).powi(2)+(z as f64-cz).powi(2)<=*radius2,
            Self::Grid { x: sx, y: sy, z: sz } => x.rem_euclid(*sx)==0&&y.rem_euclid(*sy)==0&&z.rem_euclid(*sz)==0,
            Self::Surface => {
                let state = extent.get_block(x,y,z);
                !registry.is_air_like(state) && [(1,0,0),(-1,0,0),(0,1,0),(0,-1,0),(0,0,1),(0,0,-1)].iter().any(|&(dx,dy,dz)| registry.is_air_like(extent.get_block(x+dx,y+dy,z+dz)))
            }
            Self::Chance(chance) => coordinate_chance(x,y,z) < *chance,
            Self::Noise { kind, scale, threshold } => {let(fx,fy,fz)=(x as f64*scale,y as f64*scale,z as f64*scale);let value=match kind{0=>Perlin::new(0).unit(fx,fy,fz),1=>Simplex::new(0).unit(fx,fy,fz),2=>Voronoi::new(0).unit(fx,fy,fz),_=>RidgedMultiFractal::new(0,6,2.0,2.0).unit(fx,fy,fz)};value>=*threshold},
            Self::Expression(expression) => {
                let mut vars=Variables::new().set("x",x as f64).set("y",y as f64).set("z",z as f64).set("b",extent.get_block(x,y,z) as f64);
                expression.evaluate(&mut vars).unwrap_or(0.0) > 0.0
            }
            Self::Offset { inner, x: ox, y: oy, z: oz } => inner.test(extent,registry,x+ox,y+oy,z+oz),
            Self::Adjacent { inner, min, max } => {
                let count=[(1,0,0),(-1,0,0),(0,1,0),(0,-1,0),(0,0,1),(0,0,-1)].iter().filter(|&&(dx,dy,dz)|inner.test(extent,registry,x+dx,y+dy,z+dz)).count();
                count>=*min&&count<=*max
            }
            Self::Nearby { inner, radius, min, max } => {let mut count=0usize;'scan:for dz in -*radius..=*radius{for dy in -*radius..=*radius{for dx in -*radius..=*radius{if dx==0&&dy==0&&dz==0{continue;}if inner.test(extent,registry,x+dx,y+dy,z+dz){count+=1;if count>*max{break 'scan;}}}}}count>=*min&&count<=*max}
            Self::Exposed { min, max } => {let count=[(1,0,0),(-1,0,0),(0,1,0),(0,-1,0),(0,0,1),(0,0,-1)].iter().filter(|&&(dx,dy,dz)|registry.is_air_like(extent.get_block(x+dx,y+dy,z+dz))).count();count>=*min&&count<=*max}
            Self::DirectionalExposure { x: dx, y: dy, z: dz } => !registry.is_air_like(extent.get_block(x,y,z))&&registry.is_air_like(extent.get_block(x+dx,y+dy,z+dz)),
            Self::Scale { inner, x: sx, y: sy, z: sz } => inner.test(extent,registry,(x as f64/sx).floor()as i32,(y as f64/sy).floor()as i32,(z as f64/sz).floor()as i32),
            Self::RotateY { inner, sin, cos } => inner.test(extent,registry,(x as f64*cos-z as f64*sin).round()as i32,y,(x as f64*sin+z as f64*cos).round()as i32),
            Self::Mirror { inner, axis } => match axis{0=>inner.test(extent,registry,x.abs(),y,z),1=>inner.test(extent,registry,x,y.abs(),z),_=>inner.test(extent,registry,x,y,z.abs())},
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
        "#surface" | "#wall" => return Ok(Mask::Surface),
        "#top" => return Ok(Mask::DirectionalExposure{x:0,y:1,z:0}),
        "#bottom" => return Ok(Mask::DirectionalExposure{x:0,y:-1,z:0}),
        "#north" => return Ok(Mask::DirectionalExposure{x:0,y:0,z:-1}),
        "#south" => return Ok(Mask::DirectionalExposure{x:0,y:0,z:1}),
        "#east" => return Ok(Mask::DirectionalExposure{x:1,y:0,z:0}),
        "#west" => return Ok(Mask::DirectionalExposure{x:-1,y:0,z:0}),
        _ => {}
    }
    if let Some(body) = text.strip_prefix("#y[").and_then(|s| s.strip_suffix(']')) {
        let (a, b) = body.split_once(',').or_else(|| body.split_once(".."))
            .ok_or_else(|| "#y requires [min,max]".to_string())?;
        let min: i32 = a.trim().parse().map_err(|_| "invalid minimum Y".to_string())?;
        let max: i32 = b.trim().parse().map_err(|_| "invalid maximum Y".to_string())?;
        return Ok(Mask::Height { min: min.min(max), max: min.max(max) });
    }
    if let Some(rest)=text.strip_prefix("#sphere") {let args=brackets(rest)?;if args.len()!=4{return Err("#sphere requires [x][y][z][radius]".into());}let number=|s:&str|s.trim().parse::<f64>().map_err(|_|"invalid sphere coordinate or radius".to_string());let(x,y,z,r)=(number(args[0])?,number(args[1])?,number(args[2])?,number(args[3])?);if !r.is_finite()||r<0.0{return Err("sphere radius must be non-negative".into());}return Ok(Mask::Sphere{x,y,z,radius2:r*r});}
    if let Some(rest)=text.strip_prefix("#cylinder") {let args=brackets(rest)?;if args.len()!=3{return Err("#cylinder requires [x][z][radius]".into());}let number=|s:&str|s.trim().parse::<f64>().map_err(|_|"invalid cylinder coordinate or radius".to_string());let(x,z,r)=(number(args[0])?,number(args[1])?,number(args[2])?);if !r.is_finite()||r<0.0{return Err("cylinder radius must be non-negative".into());}return Ok(Mask::Cylinder{x,z,radius2:r*r});}
    if let Some(rest)=text.strip_prefix("#grid") {let args=brackets(rest)?;if args.len()!=3{return Err("#grid requires [x][y][z]".into());}let size=|s:&str|s.trim().parse::<i32>().map_err(|_|"invalid grid spacing".to_string()).and_then(|n|if n>0{Ok(n)}else{Err("grid spacing must be positive".into())});return Ok(Mask::Grid{x:size(args[0])?,y:size(args[1])?,z:size(args[2])?});}
    if let Some(rest) = text.strip_prefix("#chance") {
        let args=brackets(rest)?;if args.len()!=1{return Err("#chance requires [probability]".into());}let mut chance=args[0].trim().trim_end_matches('%').parse::<f64>().map_err(|_|"invalid chance".to_string())?;if args[0].trim().ends_with('%')||chance>1.0{chance/=100.0;}return Ok(Mask::Chance(chance.clamp(0.0,1.0)));
    }
    if let Some(rest)=text.strip_prefix("#scale"){let args=brackets(rest)?;if args.len()!=4{return Err("#scale requires [mask][x][y][z]".into());}let scale=|s:&str|s.parse::<f64>().map_err(|_|"invalid mask scale".to_string()).and_then(|v|if v.is_finite()&&v!=0.0{Ok(v)}else{Err("mask scale must be finite and non-zero".into())});return Ok(Mask::Scale{inner:Box::new(parse_mask(reg,args[0],depth+1)?),x:scale(args[1])?,y:scale(args[2])?,z:scale(args[3])?});}
    if let Some(rest)=text.strip_prefix("#rotate"){let args=brackets(rest)?;if args.len()!=2{return Err("#rotate requires [mask][degrees]".into());}let degrees=args[1].parse::<f64>().map_err(|_|"invalid mask rotation".to_string())?;if !degrees.is_finite(){return Err("mask rotation must be finite".into());}let radians=degrees.to_radians();return Ok(Mask::RotateY{inner:Box::new(parse_mask(reg,args[0],depth+1)?),sin:radians.sin(),cos:radians.cos()});}
    if let Some(rest)=text.strip_prefix("#mirror"){let args=brackets(rest)?;if args.len()!=2{return Err("#mirror requires [mask][axis]".into());}let axis=match args[1].trim().to_ascii_lowercase().as_str(){"x"=>0,"y"=>1,"z"=>2,_=>return Err("mirror axis must be x, y, or z".into())};return Ok(Mask::Mirror{inner:Box::new(parse_mask(reg,args[0],depth+1)?),axis});}
    if let Some(rest)=text.strip_prefix("#noise"){let args=brackets(rest)?;if args.len()!=3{return Err("#noise requires [type][scale][threshold]".into());}let kind=match args[0].trim().to_ascii_lowercase().as_str(){"perlin"=>0,"simplex"=>1,"voronoi"=>2,"ridged"=>3,_=>return Err("noise type must be perlin, simplex, voronoi, or ridged".into())};let scale=args[1].parse::<f64>().map_err(|_|"invalid noise scale".to_string())?;let threshold=args[2].parse::<f64>().map_err(|_|"invalid noise threshold".to_string())?;if !scale.is_finite()||scale<=0.0||!threshold.is_finite()||!(0.0..=1.0).contains(&threshold){return Err("noise scale must be positive and threshold must be 0..1".into());}return Ok(Mask::Noise{kind,scale,threshold});}
    if let Some(rest) = text.strip_prefix("#expression") {
        let args=brackets(rest)?;if args.len()!=1{return Err("#expression requires [expression]".into());}return Ok(Mask::Expression(Arc::new(Expression::compile(args[0]).map_err(|e|e.0)?)));
    }
    if let Some(rest) = text.strip_prefix("#offset") {
        let args=brackets(rest)?;if args.len()!=4{return Err("#offset requires [mask][x][y][z]".into());}let parse=|s:&str|s.trim().parse::<i32>().map_err(|_|"invalid mask offset".to_string());return Ok(Mask::Offset{inner:Box::new(parse_mask(reg,args[0],depth+1)?),x:parse(args[1])?,y:parse(args[2])?,z:parse(args[3])?});
    }
    if let Some(rest) = text.strip_prefix("#adjacent") {
        let args=brackets(rest)?;if args.is_empty()||args.len()>3{return Err("#adjacent requires [mask][min][max]".into());}let min=args.get(1).map_or(Ok(1),|s|s.trim().parse::<usize>().map_err(|_|"invalid adjacent minimum".to_string()))?;let max=args.get(2).map_or(Ok(6),|s|s.trim().parse::<usize>().map_err(|_|"invalid adjacent maximum".to_string()))?;if min>max{return Err("adjacent minimum exceeds maximum".into());}return Ok(Mask::Adjacent{inner:Box::new(parse_mask(reg,args[0],depth+1)?),min:min.min(6),max:max.min(6)});
    }
    if let Some(rest)=text.strip_prefix("#nearby"){let args=brackets(rest)?;if args.len()<2||args.len()>4{return Err("#nearby requires [mask][radius][min][max]".into());}let radius=args[1].parse::<i32>().map_err(|_|"invalid nearby radius".to_string())?;if !(1..=8).contains(&radius){return Err("nearby radius must be 1..8".into());}let capacity=((radius*2+1).pow(3)-1)as usize;let min=args.get(2).map_or(Ok(1),|s|s.parse::<usize>().map_err(|_|"invalid nearby minimum".to_string()))?;let max=args.get(3).map_or(Ok(capacity),|s|s.parse::<usize>().map_err(|_|"invalid nearby maximum".to_string()))?;if min>max||max>capacity{return Err(format!("nearby count range must fit 0..{capacity}"));}return Ok(Mask::Nearby{inner:Box::new(parse_mask(reg,args[0],depth+1)?),radius,min,max});}
    if let Some(rest)=text.strip_prefix("#exposed"){let args=brackets(rest)?;if args.len()>2{return Err("#exposed accepts [min][max]".into());}let min=args.first().map_or(Ok(1),|s|s.parse::<usize>().map_err(|_|"invalid exposed minimum".to_string()))?;let max=args.get(1).map_or(Ok(6),|s|s.parse::<usize>().map_err(|_|"invalid exposed maximum".to_string()))?;if min>max||max>6{return Err("exposed count range must fit 0..6".into());}return Ok(Mask::Exposed{min,max});}
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

fn coordinate_chance(x:i32,y:i32,z:i32)->f64{let mut n=(x as u32 as u64).wrapping_mul(0x9E3779B185EBCA87)^(y as u32 as u64).wrapping_mul(0xC2B2AE3D27D4EB4F)^(z as u32 as u64).wrapping_mul(0x165667B19E3779F9);n^=n>>30;n=n.wrapping_mul(0xBF58476D1CE4E5B9);n^=n>>27;n=n.wrapping_mul(0x94D049BB133111EB);n^=n>>31;(n>>11)as f64/(1u64<<53)as f64}
fn brackets(mut text:&str)->Result<Vec<&str>,String>{let mut out=Vec::new();while !text.trim().is_empty(){text=text.trim();if !text.starts_with('['){return Err(format!("expected '[', found '{text}'"));}let mut depth=0;let mut end=None;for(i,c)in text.char_indices(){if c=='['{depth+=1;}else if c==']'{depth-=1;if depth==0{end=Some(i);break;}}}let end=end.ok_or_else(||"unclosed mask bracket".to_string())?;out.push(&text[1..end]);text=&text[end+1..];}Ok(out)}

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
        assert!(Mask::parse(&reg, "#surface").unwrap().test(&world,&reg,0,64,0));
        assert!(Mask::parse(&reg, "#expression[y==64]").unwrap().test(&world,&reg,0,64,0));
        assert!(Mask::parse(&reg, "#offset[stone][-1][0][0]").unwrap().test(&world,&reg,1,64,0));
        assert!(Mask::parse(&reg, "#adjacent[stone][1][6]").unwrap().test(&world,&reg,1,64,0));
        let chance=Mask::parse(&reg,"#chance[50%]").unwrap();assert_eq!(chance.test(&world,&reg,5,5,5),chance.test(&world,&reg,5,5,5));
        let sphere=Mask::parse(&reg,"#sphere[0][64][0][2]").unwrap();assert!(sphere.test(&world,&reg,2,64,0));assert!(!sphere.test(&world,&reg,3,64,0));
        let cylinder=Mask::parse(&reg,"#cylinder[0][0][2]").unwrap();assert!(cylinder.test(&world,&reg,0,999,2));assert!(!cylinder.test(&world,&reg,0,0,3));
        let grid=Mask::parse(&reg,"#grid[2][4][2]").unwrap();assert!(grid.test(&world,&reg,-2,64,2));assert!(!grid.test(&world,&reg,1,64,2));
        assert!(Mask::parse(&reg,"#nearby[stone][1][1][26]").unwrap().test(&world,&reg,1,64,0));
        assert!(Mask::parse(&reg,"#exposed[1][6]").unwrap().test(&world,&reg,0,64,0));
        let noise=Mask::parse(&reg,"#noise[perlin][0.1][0.5]").unwrap();assert_eq!(noise.test(&world,&reg,4,5,6),noise.test(&world,&reg,4,5,6));
        assert!(Mask::parse(&reg,"#top").unwrap().test(&world,&reg,0,64,0));assert!(Mask::parse(&reg,"#west").unwrap().test(&world,&reg,0,64,0));let scaled=Mask::parse(&reg,"#scale[#grid[2][1][1]][2][1][1]").unwrap();assert!(scaled.test(&world,&reg,4,64,0));let rotated=Mask::parse(&reg,"#rotate[#grid[2][1][1]][90]").unwrap();assert!(rotated.test(&world,&reg,0,64,2));let mirrored=Mask::parse(&reg,"#mirror[#offset[stone][-1][0][0]][x]").unwrap();assert!(mirrored.test(&world,&reg,-1,64,0));
    }
    #[test] fn malformed_masks_are_rejected() {
        let reg = InMemoryBlockRegistry::with_vanilla_subset();
        assert!(Mask::parse(&reg, "#y[x,4]").is_err());
        assert!(Mask::parse(&reg, "definitely_missing").is_err());
    }
}

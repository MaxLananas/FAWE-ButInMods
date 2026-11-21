//! Complete FAWE brush catalogue and per-player brush settings.
use std::collections::HashMap;
use crate::selection::SelectionKey;

pub const BRUSH_NAMES:[&str;46]=["biome","blendball","butcher","catenary","circle","cliff","clipboard","command","copypaste","cylinder","deform","dilate","erode","extinguish","feature","flatten","forest","gravity","height","heightmap","image","item","layer","line","lower","morph","populateschematic","pull","raise","recursive","rock","scatter","scattercommand","set","shatter","smooth","snow","snowsmooth","sphere","splatter","spline","stencil","structure","surface","surfacespline","sweep"];

pub fn canonical(name:&str)->Option<&'static str>{let n=name.to_ascii_lowercase();for &v in &BRUSH_NAMES{if n==v{return Some(v);}}match n.as_str(){"bb"|"blend"=>Some("blendball"),"cat"|"gravityline"|"saggedline"=>Some("catenary"),"copy"=>Some("clipboard"),"cmd"=>Some("command"),"cp"|"copypasta"=>Some("copypaste"),"cyl"|"c"=>Some("cylinder"),"ex"=>Some("extinguish"),"flatmap"|"flat"=>Some("flatten"),"grav"=>Some("gravity"),"l"=>Some("line"),"recurse"|"r"=>Some("recursive"),"blob"=>Some("rock"),"s"=>Some("sphere"),"splat"=>Some("splatter"),"spl"|"curve"=>Some("spline"),"surf"=>Some("surface"),"sspline"|"sspl"=>Some("surfacespline"),"sw"|"vaesweep"=>Some("sweep"),_=>None}}

#[derive(Clone,Debug)]pub struct BrushSettings{pub kind:&'static str,pub radius:f64,pub pattern:String,pub mask:Option<String>}
impl Default for BrushSettings{fn default()->Self{Self{kind:"sphere",radius:2.0,pattern:"stone".into(),mask:None}}}
#[derive(Default)]pub struct BrushManager{brushes:HashMap<SelectionKey,BrushSettings>}
impl BrushManager{pub fn get(&self,key:SelectionKey)->Option<&BrushSettings>{self.brushes.get(&key)}pub fn set(&mut self,key:SelectionKey,value:BrushSettings){self.brushes.insert(key,value);}pub fn clear(&mut self,key:SelectionKey)->bool{self.brushes.remove(&key).is_some()}}

#[cfg(test)]mod tests{use super::*;#[test]fn all_46_reference_brushes_and_aliases_are_present(){assert_eq!(BRUSH_NAMES.len(),46);for name in BRUSH_NAMES{assert_eq!(canonical(name),Some(name));}assert_eq!(canonical("bb"),Some("blendball"));assert_eq!(canonical("sspl"),Some("surfacespline"));assert_eq!(canonical("missing"),None);}}

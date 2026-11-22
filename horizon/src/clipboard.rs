//! In-memory block clipboards and copy/cut/paste transformations.

use std::collections::HashMap;
use crate::math::BlockVector3;
use crate::region::RegionShape;
use crate::selection::SelectionKey;
use crate::world::Extent;

#[derive(Clone, Debug)]
pub struct Clipboard {
    size: BlockVector3,
    origin: BlockVector3,
    blocks: Vec<i32>,
}

impl Clipboard {
    pub fn capture(extent: &dyn Extent, region: &mut RegionShape) -> Self {
        let min = region.min(); let max = region.max();
        let size = BlockVector3::at(max.x - min.x + 1, max.y - min.y + 1, max.z - min.z + 1);
        let mut blocks = vec![-1; (size.x as usize) * (size.y as usize) * (size.z as usize)];
        region.for_each_position(|x,y,z| {
            let i = ((y-min.y) * size.z * size.x + (z-min.z) * size.x + (x-min.x)) as usize;
            blocks[i] = extent.get_block(x,y,z);
        });
        Self { size, origin: min, blocks }
    }
    pub(crate) fn from_parts(size: BlockVector3, origin: BlockVector3, blocks: Vec<i32>) -> Self { Self { size, origin, blocks } }
    pub const fn size(&self) -> BlockVector3 { self.size }
    pub const fn origin(&self) -> BlockVector3 { self.origin }
    pub fn set_origin(&mut self, origin: BlockVector3) { self.origin = origin; }
    pub fn volume(&self) -> usize { self.blocks.iter().filter(|&&s| s >= 0).count() }
    fn index(&self, x:i32,y:i32,z:i32)->usize { (y*self.size.z*self.size.x + z*self.size.x + x) as usize }
    pub fn get(&self,x:i32,y:i32,z:i32)->i32 { self.blocks[self.index(x,y,z)] }
    pub fn for_each(&self, mut f: impl FnMut(i32,i32,i32,i32)) {
        for y in 0..self.size.y { for z in 0..self.size.z { for x in 0..self.size.x {
            let state=self.get(x,y,z); if state>=0 { f(x,y,z,state); }
        }}}
    }
    pub fn rotate_y(&mut self, quarter_turns: i32) { self.rotate_axis('y',quarter_turns); }
    pub fn rotate_axis(&mut self, axis:char, quarter_turns:i32) {
        for _ in 0..quarter_turns.rem_euclid(4) {
            let old=self.clone();
            self.size=match axis {'x'=>BlockVector3::at(old.size.x,old.size.z,old.size.y),'z'=>BlockVector3::at(old.size.y,old.size.x,old.size.z),_=>BlockVector3::at(old.size.z,old.size.y,old.size.x)};
            self.blocks=vec![-1;(self.size.x*self.size.y*self.size.z) as usize];
            old.for_each(|x,y,z,state| {let(nx,ny,nz)=match axis{'x'=>(x,old.size.z-1-z,y),'z'=>(old.size.y-1-y,x,z),_=>(old.size.z-1-z,y,x)};let i=self.index(nx,ny,nz);self.blocks[i]=state;});
        }
    }
    pub fn flip(&mut self, axis: char) {
        let old=self.clone();
        old.for_each(|x,y,z,state| { let (nx,ny,nz)=match axis {'x'=>(old.size.x-1-x,y,z),'y'=>(x,old.size.y-1-y,z),_=>(x,y,old.size.z-1-z)}; let i=self.index(nx,ny,nz); self.blocks[i]=state; });
    }
    pub fn scale(&mut self, sx:i32, sy:i32, sz:i32) {
        let old=self.clone();self.size=BlockVector3::at(old.size.x*sx,old.size.y*sy,old.size.z*sz);self.blocks=vec![-1;(self.size.x*self.size.y*self.size.z)as usize];
        old.for_each(|x,y,z,state|for dy in 0..sy{for dz in 0..sz{for dx in 0..sx{let i=self.index(x*sx+dx,y*sy+dy,z*sz+dz);self.blocks[i]=state;}}});
    }
    pub fn shift_origin(&mut self, dx:i32, dy:i32, dz:i32) { self.origin=BlockVector3::at(self.origin.x.saturating_add(dx),self.origin.y.saturating_add(dy),self.origin.z.saturating_add(dz)); }
    pub fn checksum(&self)->u64{let mut hash=0xcbf29ce484222325u64;self.for_each(|x,y,z,state|{hash^=(state as u32 as u64)^((x as u32 as u64)<<32)^(y as u32 as u64).rotate_left(17)^(z as u32 as u64).rotate_left(41);hash=hash.wrapping_mul(0x100000001b3);});hash}
    pub fn count_state(&self,state:i32)->usize{self.blocks.iter().filter(|&&s|s==state).count()}
    pub fn map_states(&mut self, mut transform:impl FnMut(i32)->i32) { for state in &mut self.blocks { if *state>=0 { *state=transform(*state); } } }
    pub fn replace_state(&mut self, from:i32, mut replacement:impl FnMut(i32,i32,i32)->i32)->usize {let mut changed=0;for y in 0..self.size.y{for z in 0..self.size.z{for x in 0..self.size.x{let i=self.index(x,y,z);if self.blocks[i]==from{self.blocks[i]=replacement(x,y,z);changed+=1;}}}}changed}
    pub fn trim(&mut self, mut keep:impl FnMut(i32)->bool)->bool {
        let mut min=self.size;let mut max=BlockVector3::at(-1,-1,-1);self.for_each(|x,y,z,state|if keep(state){min.x=min.x.min(x);min.y=min.y.min(y);min.z=min.z.min(z);max.x=max.x.max(x);max.y=max.y.max(y);max.z=max.z.max(z);});if max.x<0{return false;}if min==BlockVector3::ZERO&&max==BlockVector3::at(self.size.x-1,self.size.y-1,self.size.z-1){return false;}let old=self.clone();self.size=BlockVector3::at(max.x-min.x+1,max.y-min.y+1,max.z-min.z+1);self.origin=BlockVector3::at(old.origin.x+min.x,old.origin.y+min.y,old.origin.z+min.z);self.blocks=vec![-1;(self.size.x*self.size.y*self.size.z)as usize];old.for_each(|x,y,z,state|if x>=min.x&&x<=max.x&&y>=min.y&&y<=max.y&&z>=min.z&&z<=max.z{let i=self.index(x-min.x,y-min.y,z-min.z);self.blocks[i]=state;});true
    }
}

#[derive(Default)]
pub struct ClipboardManager { clips: HashMap<SelectionKey, Clipboard> }
impl ClipboardManager {
    pub fn get(&self,key:SelectionKey)->Option<&Clipboard>{self.clips.get(&key)}
    pub fn get_mut(&mut self,key:SelectionKey)->Option<&mut Clipboard>{self.clips.get_mut(&key)}
    pub fn set(&mut self,key:SelectionKey,clip:Clipboard){self.clips.insert(key,clip);}
    pub fn clear(&mut self,key:SelectionKey)->bool{self.clips.remove(&key).is_some()}
}

#[cfg(test)] mod tests {
 use super::*; use crate::region::{CuboidRegion,RegionShape}; use crate::world::{InMemoryWorld,Extent};
 #[test] fn capture_rotate_flip_preserves_blocks(){
  let mut w=InMemoryWorld::default(); w.set_block(0,0,0,1);w.set_block(1,0,0,2);w.set_block(0,0,1,3);w.set_block(1,0,1,4);
  let mut r=RegionShape::Cuboid(CuboidRegion::new(BlockVector3::at(0,0,0),BlockVector3::at(1,0,1)));
  let mut c=Clipboard::capture(&w,&mut r);assert_eq!(c.volume(),4);c.rotate_y(1);assert_eq!(c.get(1,0,0),1);c.flip('x');assert_eq!(c.get(0,0,0),1);c.scale(2,1,2);assert_eq!(c.size(),BlockVector3::at(4,1,4));assert_eq!(c.get(0,0,0),1);c.shift_origin(2,3,4);assert_eq!(c.origin(),BlockVector3::at(2,3,4));assert_eq!(c.replace_state(1,|_,_,_|9),4);assert_eq!(c.get(0,0,0),9);assert_eq!(c.count_state(9),4);assert_eq!(c.checksum(),c.checksum());let original=c.clone();for axis in ['x','y','z']{let mut turned=original.clone();turned.rotate_axis(axis,4);assert_eq!(turned.size(),original.size());assert_eq!(turned.blocks,original.blocks);}
 }
 #[test]fn trim_removes_empty_borders(){let c=Clipboard::from_parts(BlockVector3::at(3,3,3),BlockVector3::at(10,20,30),{let mut b=vec![0;27];b[13]=7;b});let mut c=c;assert!(c.trim(|s|s!=0));assert_eq!(c.size(),BlockVector3::at(1,1,1));assert_eq!(c.origin(),BlockVector3::at(11,21,31));assert_eq!(c.get(0,0,0),7);}
}

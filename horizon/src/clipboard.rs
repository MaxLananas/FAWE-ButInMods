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
    pub fn volume(&self) -> usize { self.blocks.iter().filter(|&&s| s >= 0).count() }
    fn index(&self, x:i32,y:i32,z:i32)->usize { (y*self.size.z*self.size.x + z*self.size.x + x) as usize }
    pub fn get(&self,x:i32,y:i32,z:i32)->i32 { self.blocks[self.index(x,y,z)] }
    pub fn for_each(&self, mut f: impl FnMut(i32,i32,i32,i32)) {
        for y in 0..self.size.y { for z in 0..self.size.z { for x in 0..self.size.x {
            let state=self.get(x,y,z); if state>=0 { f(x,y,z,state); }
        }}}
    }
    pub fn rotate_y(&mut self, quarter_turns: i32) {
        for _ in 0..quarter_turns.rem_euclid(4) {
            let old=self.clone();
            self.size=BlockVector3::at(old.size.z,old.size.y,old.size.x);
            self.blocks=vec![-1;(self.size.x*self.size.y*self.size.z) as usize];
            old.for_each(|x,y,z,state| { let nx=old.size.z-1-z; let nz=x; let i=self.index(nx,y,nz); self.blocks[i]=state; });
        }
    }
    pub fn flip(&mut self, axis: char) {
        let old=self.clone();
        old.for_each(|x,y,z,state| { let (nx,ny,nz)=match axis {'x'=>(old.size.x-1-x,y,z),'y'=>(x,old.size.y-1-y,z),_=>(x,y,old.size.z-1-z)}; let i=self.index(nx,ny,nz); self.blocks[i]=state; });
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
  let mut c=Clipboard::capture(&w,&mut r);assert_eq!(c.volume(),4);c.rotate_y(1);assert_eq!(c.get(1,0,0),1);c.flip('x');assert_eq!(c.get(0,0,0),1);
 }
}

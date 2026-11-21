//! Allocation-light geometric generators and region boundary operations.

use std::collections::HashSet;
use crate::math::BlockVector3;

pub fn line(a: BlockVector3, b: BlockVector3, thickness: f64, mut visit: impl FnMut(i32,i32,i32)) -> u64 {
    let dx=(b.x-a.x) as f64;let dy=(b.y-a.y) as f64;let dz=(b.z-a.z) as f64;
    let steps=dx.abs().max(dy.abs()).max(dz.abs()).max(1.0) as i32;
    let radius=((thickness.max(1.0)-1.0)/2.0).ceil() as i32;let mut seen=HashSet::new();
    for i in 0..=steps { let t=i as f64/steps as f64;let cx=(a.x as f64+dx*t).round() as i32;let cy=(a.y as f64+dy*t).round() as i32;let cz=(a.z as f64+dz*t).round() as i32;
        for y in cy-radius..=cy+radius {for z in cz-radius..=cz+radius{for x in cx-radius..=cx+radius{
            let d2=((x-cx)*(x-cx)+(y-cy)*(y-cy)+(z-cz)*(z-cz)) as f64;
            if d2<=(thickness.max(1.0)/2.0).powi(2)+0.75 && seen.insert((x,y,z)){visit(x,y,z);}
        }}}
    } seen.len() as u64
}

pub fn sphere(center: BlockVector3, rx:f64, ry:f64, rz:f64, hollow:bool, mut visit:impl FnMut(i32,i32,i32))->u64{
    let (rx,ry,rz)=(rx.abs().max(0.5),ry.abs().max(0.5),rz.abs().max(0.5));let mut n=0;
    for y in center.y-(ry.ceil() as i32)..=center.y+ry.ceil() as i32 {for z in center.z-(rz.ceil() as i32)..=center.z+rz.ceil() as i32{for x in center.x-(rx.ceil() as i32)..=center.x+rx.ceil() as i32{
        let norm=((x-center.x)as f64/rx).powi(2)+((y-center.y)as f64/ry).powi(2)+((z-center.z)as f64/rz).powi(2);
        if norm<=1.0 {if hollow {let inner=((x-center.x)as f64/(rx-1.0).max(0.1)).powi(2)+((y-center.y)as f64/(ry-1.0).max(0.1)).powi(2)+((z-center.z)as f64/(rz-1.0).max(0.1)).powi(2);if inner<1.0{continue;}}visit(x,y,z);n+=1;}
    }}} n
}

pub fn cylinder(center:BlockVector3,rx:f64,rz:f64,height:i32,hollow:bool,mut visit:impl FnMut(i32,i32,i32))->u64{
    let (rx,rz)=(rx.abs().max(0.5),rz.abs().max(0.5));let (lo,hi)=if height>=0{(center.y,center.y+height.max(1)-1)}else{(center.y+height+1,center.y)};let mut n=0;
    for y in lo..=hi{for z in center.z-rz.ceil()as i32..=center.z+rz.ceil()as i32{for x in center.x-rx.ceil()as i32..=center.x+rx.ceil()as i32{
        let d=((x-center.x)as f64/rx).powi(2)+((z-center.z)as f64/rz).powi(2);if d<=1.0{if hollow{let inner=((x-center.x)as f64/(rx-1.0).max(0.1)).powi(2)+((z-center.z)as f64/(rz-1.0).max(0.1)).powi(2);if inner<1.0{continue;}}visit(x,y,z);n+=1;}
    }}}n
}

pub fn pyramid(center:BlockVector3,size:i32,hollow:bool,mut visit:impl FnMut(i32,i32,i32))->u64{let mut n=0;let size=size.abs().max(1);for layer in 0..size{let r=size-layer-1;let y=center.y+layer;for z in center.z-r..=center.z+r{for x in center.x-r..=center.x+r{if !hollow||layer==size-1||x==center.x-r||x==center.x+r||z==center.z-r||z==center.z+r{visit(x,y,z);n+=1;}}}}n}

pub fn cone(center:BlockVector3,radius:f64,height:i32,hollow:bool,mut visit:impl FnMut(i32,i32,i32))->u64{let height=height.max(1);let mut n=0;for layer in 0..height{let r=radius.abs()*(1.0-layer as f64/height as f64);let inner=(r-1.0).max(0.0);for z in center.z-r.ceil()as i32..=center.z+r.ceil()as i32{for x in center.x-r.ceil()as i32..=center.x+r.ceil()as i32{let d=(((x-center.x)*(x-center.x)+(z-center.z)*(z-center.z))as f64).sqrt();if d<=r&&(!hollow||d>=inner||layer==height-1){visit(x,center.y+layer,z);n+=1;}}}}n}
pub fn torus(center:BlockVector3,major:f64,minor:f64,hollow:bool,mut visit:impl FnMut(i32,i32,i32))->u64{let(major,minor)=(major.abs().max(1.0),minor.abs().max(0.5));let bound=(major+minor).ceil()as i32;let mut n=0;for y in center.y-minor.ceil()as i32..=center.y+minor.ceil()as i32{for z in center.z-bound..=center.z+bound{for x in center.x-bound..=center.x+bound{let radial=(((x-center.x).pow(2)+(z-center.z).pow(2))as f64).sqrt()-major;let d2=radial*radial+(y-center.y).pow(2)as f64;if d2<=minor*minor&&(!hollow||d2>=(minor-1.0).max(0.0).powi(2)){visit(x,y,z);n+=1;}}}}n}

#[cfg(test)]mod tests{use super::*;#[test]fn generators_are_bounded_and_hollow_is_smaller(){let c=BlockVector3::at(0,0,0);let mut a=0;let solid=sphere(c,4.,4.,4.,false,|_,_,_|a+=1);let hollow=sphere(c,4.,4.,4.,true,|_,_,_|{});assert_eq!(a,solid);assert!(solid>hollow&&hollow>0);assert_eq!(cylinder(c,2.,2.,3,false,|_,_,_|{}),39);assert_eq!(pyramid(c,3,false,|_,_,_|{}),35);assert!(cone(c,4.,5,false,|_,_,_|{})>cone(c,4.,5,true,|_,_,_|{}));assert!(torus(c,5.,2.,false,|_,_,_|{})>torus(c,5.,2.,true,|_,_,_|{}));}#[test]fn line_includes_endpoints(){let mut pts=HashSet::new();line(BlockVector3::at(0,0,0),BlockVector3::at(4,0,0),1.,|x,y,z|{pts.insert((x,y,z));});assert!(pts.contains(&(0,0,0))&&pts.contains(&(4,0,0)));}}

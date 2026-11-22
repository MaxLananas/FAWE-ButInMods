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

pub fn hollow_line(a:BlockVector3,b:BlockVector3,thickness:f64,mut visit:impl FnMut(i32,i32,i32))->u64{let mut outer=HashSet::new();line(a,b,thickness,|x,y,z|{outer.insert((x,y,z));});if thickness>2.0{line(a,b,(thickness-2.0).max(1.0),|x,y,z|{outer.remove(&(x,y,z));});}for&(x,y,z)in &outer{visit(x,y,z);}outer.len()as u64}

pub fn quadratic_bezier(a:BlockVector3,control:BlockVector3,b:BlockVector3,thickness:f64,mut visit:impl FnMut(i32,i32,i32))->u64{let estimate=((a.x-control.x).abs()+(a.y-control.y).abs()+(a.z-control.z).abs()+(control.x-b.x).abs()+(control.y-b.y).abs()+(control.z-b.z).abs()).max(1)*2;let mut seen=HashSet::new();let mut previous=a;for i in 1..=estimate{let t=i as f64/estimate as f64;let u=1.0-t;let next=BlockVector3::at((u*u*a.x as f64+2.0*u*t*control.x as f64+t*t*b.x as f64).round()as i32,(u*u*a.y as f64+2.0*u*t*control.y as f64+t*t*b.y as f64).round()as i32,(u*u*a.z as f64+2.0*u*t*control.z as f64+t*t*b.z as f64).round()as i32);line(previous,next,thickness,|x,y,z|{if seen.insert((x,y,z)){visit(x,y,z);}});previous=next;}seen.len()as u64}

pub fn helix(center:BlockVector3,radius:f64,height:i32,turns:f64,thickness:f64,mut visit:impl FnMut(i32,i32,i32))->u64{let steps=((radius.abs()*turns.abs()*12.0).ceil()as i32).max(height.abs()*4).max(1);let mut seen=HashSet::new();let mut previous=BlockVector3::at(center.x+(radius.round()as i32),center.y,center.z);for i in 1..=steps{let t=i as f64/steps as f64;let angle=t*turns*std::f64::consts::TAU;let next=BlockVector3::at(center.x+(angle.cos()*radius).round()as i32,center.y+(t*height as f64).round()as i32,center.z+(angle.sin()*radius).round()as i32);line(previous,next,thickness,|x,y,z|{if seen.insert((x,y,z)){visit(x,y,z);}});previous=next;}seen.len()as u64}

pub fn capsule(center:BlockVector3,radius:f64,height:i32,hollow:bool,mut visit:impl FnMut(i32,i32,i32))->u64{let radius=radius.abs().max(0.5);let height=height.max(1);let top=BlockVector3::at(center.x,center.y+height-1,center.z);let mut points=HashSet::new();cylinder(center,radius,radius,height,hollow,|x,y,z|{points.insert((x,y,z));});sphere(center,radius,radius,radius,hollow,|x,y,z|if y<=center.y{points.insert((x,y,z));});sphere(top,radius,radius,radius,hollow,|x,y,z|if y>=top.y{points.insert((x,y,z));});for&(x,y,z)in &points{visit(x,y,z);}points.len()as u64}

pub fn staircase(center:BlockVector3,length:i32,width:i32,rise:i32,direction:(i32,i32),solid:bool,mut visit:impl FnMut(i32,i32,i32))->u64{let length=length.clamp(1,4096);let width=width.clamp(1,256);let rise=rise.clamp(1,4096);let mut seen=HashSet::new();for step in 0..length{let level=step*rise/length;let(cx,cz)=(center.x+direction.0*step,center.z+direction.1*step);for w in 0..width{let side=w-(width-1)/2;let(x,z)=if direction.0!=0{(cx,cz+side)}else{(cx+side,cz)};let lo=if solid{center.y}else{center.y+level};for y in lo..=center.y+level{if seen.insert((x,y,z)){visit(x,y,z);}}}}seen.len()as u64}

pub fn cuboid(a:BlockVector3,b:BlockVector3,hollow:bool,mut visit:impl FnMut(i32,i32,i32))->u64{let(minx,maxx)=(a.x.min(b.x),a.x.max(b.x));let(miny,maxy)=(a.y.min(b.y),a.y.max(b.y));let(minz,maxz)=(a.z.min(b.z),a.z.max(b.z));let mut n=0;for y in miny..=maxy{for z in minz..=maxz{for x in minx..=maxx{if !hollow||x==minx||x==maxx||y==miny||y==maxy||z==minz||z==maxz{visit(x,y,z);n+=1;}}}}n}

pub fn regular_prism(center:BlockVector3,sides:i32,radius:f64,height:i32,hollow:bool,mut visit:impl FnMut(i32,i32,i32))->u64{let sides=sides.clamp(3,64);let radius=radius.abs().max(0.5);let vertices=(0..sides).map(|i|{let a=std::f64::consts::TAU*i as f64/sides as f64;(center.x as f64+a.cos()*radius,center.z as f64+a.sin()*radius)}).collect::<Vec<_>>();let inside=|x:i32,z:i32|{let mut sign=0i8;for i in 0..vertices.len(){let(a,b)=(vertices[i],vertices[(i+1)%vertices.len()]);let cross=(b.0-a.0)*(z as f64-a.1)-(b.1-a.1)*(x as f64-a.0);let s=if cross>1e-9{1}else if cross< -1e-9{-1}else{0};if s!=0{if sign!=0&&sign!=s{return false;}sign=s;}}true};let(lo,hi)=if height>=0{(center.y,center.y+height.max(1)-1)}else{(center.y+height+1,center.y)};let bound=radius.ceil()as i32;let mut n=0;for y in lo..=hi{for z in center.z-bound..=center.z+bound{for x in center.x-bound..=center.x+bound{if inside(x,z)&&(!hollow||y==lo||y==hi||[(1,0),(-1,0),(0,1),(0,-1)].iter().any(|&(dx,dz)|!inside(x+dx,z+dz))){visit(x,y,z);n+=1;}}}}n}

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

#[cfg(test)]mod tests{use super::*;#[test]fn generators_are_bounded_and_hollow_is_smaller(){let c=BlockVector3::at(0,0,0);let mut a=0;let solid=sphere(c,4.,4.,4.,false,|_,_,_|a+=1);let hollow=sphere(c,4.,4.,4.,true,|_,_,_|{});assert_eq!(a,solid);assert!(solid>hollow&&hollow>0);assert_eq!(cylinder(c,2.,2.,3,false,|_,_,_|{}),39);assert_eq!(pyramid(c,3,false,|_,_,_|{}),35);assert!(cone(c,4.,5,false,|_,_,_|{})>cone(c,4.,5,true,|_,_,_|{}));assert!(torus(c,5.,2.,false,|_,_,_|{})>torus(c,5.,2.,true,|_,_,_|{}));assert_eq!(cuboid(c,BlockVector3::at(2,2,2),false,|_,_,_|{}),27);assert_eq!(cuboid(c,BlockVector3::at(2,2,2),true,|_,_,_|{}),26);assert!(regular_prism(c,6,5.,4,false,|_,_,_|{})>regular_prism(c,6,5.,4,true,|_,_,_|{}));assert!(staircase(c,10,3,5,(1,0),true,|_,_,_|{})>staircase(c,10,3,5,(1,0),false,|_,_,_|{}));assert!(capsule(c,3.,8,false,|_,_,_|{})>capsule(c,3.,8,true,|_,_,_|{}));}#[test]fn line_includes_endpoints(){let mut pts=HashSet::new();line(BlockVector3::at(0,0,0),BlockVector3::at(4,0,0),1.,|x,y,z|{pts.insert((x,y,z));});assert!(pts.contains(&(0,0,0))&&pts.contains(&(4,0,0)));assert!(line(BlockVector3::at(0,0,0),BlockVector3::at(8,0,0),5.,|_,_,_|{})>hollow_line(BlockVector3::at(0,0,0),BlockVector3::at(8,0,0),5.,|_,_,_|{}));let mut curve=HashSet::new();quadratic_bezier(BlockVector3::at(0,0,0),BlockVector3::at(2,4,0),BlockVector3::at(4,0,0),1.,|x,y,z|{curve.insert((x,y,z));});assert!(curve.contains(&(0,0,0))&&curve.contains(&(4,0,0))&&curve.iter().any(|p|p.1>0));assert!(helix(BlockVector3::at(0,0,0),3.,8,2.,1.,|_,_,_|{})>8);}}

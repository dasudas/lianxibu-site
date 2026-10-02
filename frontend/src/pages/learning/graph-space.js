// The existing site's perspective camera; layout is independent of private progress.
export function layout3D(ids) {
 return new Map(ids.map((id,i)=>{const angle=i*2.399963229728653,radius=ids.length===1?0:195+(i%3)*24;return [id,{x:Math.cos(angle)*radius,y:Math.sin(angle)*radius*.65,z:Math.sin(angle*1.7+.5)*170}];}));
}
export function project3D(point,{yaw=0,pitch=0,scale=1,width,height,x=0,y=0,camera=1000}) {
 const rx=point.x*Math.cos(yaw)+point.z*Math.sin(yaw),rz=-point.x*Math.sin(yaw)+point.z*Math.cos(yaw),ry=point.y*Math.cos(pitch)-rz*Math.sin(pitch),depth=point.y*Math.sin(pitch)+rz*Math.cos(pitch),factor=camera/(camera-depth);
 return {x:width/2+x+rx*factor*scale,y:height/2+y+ry*factor*scale,factor,depth:Math.max(.25,Math.min(1,.7+depth/camera))};
}

/* Original rigid motion equations from sequence_editor.js. */
const clone = x => JSON.parse(JSON.stringify(x));
  const identity = () => ({position:[0,0,0], quaternion:[0,0,0,1]});
  const add = (a,b) => a.map((v,i)=>v+b[i]);
  const sub = (a,b) => a.map((v,i)=>v-b[i]);
  const scale = (a,s) => a.map(v=>v*s);
  const dot = (a,b) => a.reduce((s,v,i)=>s+v*b[i],0);
  const cross = (a,b) => [a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]];
  const normalize = a => {const n=Math.hypot(...a);if(n<1e-12)throw Error('零长度向量');return scale(a,1/n);};
  function qmul(a,b) {return [a[3]*b[0]+a[0]*b[3]+a[1]*b[2]-a[2]*b[1],a[3]*b[1]-a[0]*b[2]+a[1]*b[3]+a[2]*b[0],a[3]*b[2]+a[0]*b[1]-a[1]*b[0]+a[2]*b[3],a[3]*b[3]-dot(a.slice(0,3),b.slice(0,3))];}
  function rotate(q,v) {const t=scale(cross(q.slice(0,3),v),2);return add(v,add(scale(t,q[3]),cross(q.slice(0,3),t)));}
  function eulerXYZ(degrees) {const q=degrees.map((d,i)=>{const a=[0,0,0,Math.cos(d*Math.PI/360)];a[i]=Math.sin(d*Math.PI/360);return a;});return normalize(qmul(q[2],qmul(q[1],q[0])));}
  function slerp(a,b,t) {let c=dot(a,b),v=b;if(c<0){v=scale(b,-1);c=-c;}c=Math.min(1,Math.max(-1,c));if(c>.9995)return normalize(a.map((x,i)=>x+t*(v[i]-x)));const theta=Math.acos(c),s=Math.sin(theta);return a.map((x,i)=>(x*Math.sin((1-t)*theta)+v[i]*Math.sin(t*theta))/s);}
  function increment(pose, delta, dq, pivot) {return {position:add(add(rotate(dq,sub(pose.position,pivot)),pivot),delta),quaternion:normalize(qmul(dq,pose.quaternion))};}
  function interpolate(a,b,t) {return {position:a.position.map((v,i)=>v+(b.position[i]-v)*t),quaternion:slerp(a.quaternion,b.quaternion,t)};}
  function transitionPoses(start,end,action,t){if(!action)return clone(start);const result=clone(start),dq=slerp([0,0,0,1],action.quaternion,t);for(const id of action.ids)result[id]=increment(start[id],scale(action.translation,t),dq,action.pivot);return result;}
  
export {transitionPoses};

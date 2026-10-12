/**
 * Adapted from https://github.com/Y-jtt/westud-login-animation
 * Copyright (c) 2026 Y-jtt. MIT; see /admin/licenses/westud-login-animation.txt.
 * Vue lifecycle adapter: all animation frames, timers and listeners are owned
 * by one mounted login scene and released on disposal.
 */
export function createLoginAnimation(root, { reducedMotion = false, celebrateConfetti = true } = {}) {
  const frames = new Set(), timeouts = new Set(), intervals = new Set()
  const listeners = []
  let disposed = false
  const requestAnimationFrame = callback => {
    if (disposed) return null
    const id = window.requestAnimationFrame(time => { frames.delete(id); if (!disposed) callback(time) })
    frames.add(id)
    return id
  }
  const cancelAnimationFrame = id => { window.cancelAnimationFrame(id); frames.delete(id) }
  const setTimeout = (callback, delay) => {
    if (disposed) return null
    const id = window.setTimeout(() => { timeouts.delete(id); if (!disposed) callback() }, delay)
    timeouts.add(id)
    return id
  }
  const clearTimeout = id => { window.clearTimeout(id); timeouts.delete(id) }
  const setInterval = (callback, delay) => {
    if (disposed) return null
    const id = window.setInterval(() => { if (!disposed) callback() }, delay)
    intervals.add(id)
    return id
  }
  const clearInterval = id => { window.clearInterval(id); intervals.delete(id) }
  const listen = (target, name, handler) => {
    target.addEventListener(name, handler, { passive: true })
    listeners.push(() => target.removeEventListener(name, handler))
  }
class Spring {
  constructor(k, b) {
    this.k = k || 200; this.b = b || 24;
    this._p = 0; this._v = 0; this._t = 0;
    this._raf = null;
    this.onChange = null;
  }
  get value() { return this._p; }
  snap(v) {
    this._p = v; this._v = 0; this._t = v;
    if (this._raf) { cancelAnimationFrame(this._raf); this._raf = null; }
    if (this.onChange) this.onChange(v);
  }
  set(t) {
    if (reducedMotion) { this.snap(t); return; }
    this._t = t;
    if (!this._raf) this._tick();
  }
  _tick() {
    const dt = 1/60;
    this._v += (-this.k*(this._p-this._t) - this.b*this._v) * dt;
    this._p += this._v * dt;
    if (this.onChange) this.onChange(this._p);
    const done = Math.abs(this._p-this._t)<0.002 && Math.abs(this._v)<0.002;
    if (done) {
      this._p = this._t; this._v = 0;
      if (this.onChange) this.onChange(this._p);
      this._raf = null;
    } else {
      this._raf = requestAnimationFrame(()=>this._tick());
    }
  }
  stop() { if(this._raf){cancelAnimationFrame(this._raf);this._raf=null;} }
}

/* ═══════════════════════════════════════════════════
   DOM REFS
═══════════════════════════════════════════════════ */
const $ = id => root.querySelector(`#${id}`);
const svg = $('sceneSVG');
function sa(el,k,v){ if(el) el.setAttribute(k,v); }

const gPurple=$('gPurple'), gBlack=$('gBlack'), gYellow=$('gYellow'), gOrange=$('gOrange');
const purpleArms=$('purpleArms'), blackArms=$('blackArms');

// Pupils
const pPupilL=$('pPupilL'), pPupilR=$('pPupilR');
const bPupilL=$('bPupilL'), bPupilR=$('bPupilR');
const yPupil=$('yPupil');
const oPupilL=$('oPupilL'), oPupilR=$('oPupilR');

// Facial features
const pBrowL=$('pBrowL'), pBrowR=$('pBrowR'), pMouthSad=$('pMouthSad');
const bBrowL=$('bBrowL'), bBrowR=$('bBrowR'), bMouthSad=$('bMouthSad');
const bLidL=$('bLidL'), bLidR=$('bLidR');
const yBrow=$('yBrow'), yMouth=$('yMouth');
const oBrowL=$('oBrowL'), oBrowR=$('oBrowR'), oMouth=$('oMouth');
const beakU=$('beakU'), beakLo=$('beakLo');

/* ═══════════════════════════════════════════════════
   SPRINGS
═══════════════════════════════════════════════════ */
// Group Y offsets
const SPY = {
  purple: new Spring(180,22),
  black:  new Spring(180,22),
  yellow: new Spring(180,22),
  orange: new Spring(180,22),
};
// Purple tilt
const SPR_purple = new Spring(160,20);

// Wire group transforms
// Purple pivot ≈ center of rect (207, 290)
function applyPurple() {
  sa(gPurple,'transform',
    `translate(0,${SPY.purple.value}) rotate(${SPR_purple.value},207,290)`);
}
SPY.purple.onChange  = applyPurple;
SPR_purple.onChange  = applyPurple;
SPY.black.onChange   = v => sa(gBlack,  'transform', `translate(0,${v})`);
SPY.yellow.onChange  = v => sa(gYellow, 'transform', `translate(0,${v})`);
SPY.orange.onChange  = v => sa(gOrange, 'transform', `translate(0,${v})`);

// Pupil springs — offset from origin center
const pupilDefs = [
  { key:'pL', el:pPupilL, ox:163, oy:175, maxR:5   },
  { key:'pR', el:pPupilR, ox:233, oy:175, maxR:5   },
  { key:'bL', el:bPupilL, ox:302, oy:270, maxR:4   },
  { key:'bR', el:bPupilR, ox:350, oy:270, maxR:4   },
  { key:'yP', el:yPupil,  ox:400, oy:318, maxR:3.5 },
  { key:'oL', el:oPupilL, ox:80,  oy:358, maxR:3   },
  { key:'oR', el:oPupilR, ox:130, oy:358, maxR:3   },
];
const PSP = {}; // pupil springs by key
pupilDefs.forEach(p => {
  const sx = new Spring(300,28), sy = new Spring(300,28);
  sx.onChange = v => sa(p.el,'cx', p.ox + v);
  sy.onChange = v => sa(p.el,'cy', p.oy + v);
  PSP[p.key] = { x:sx, y:sy, maxR:p.maxR, ox:p.ox, oy:p.oy };
});

// Lid + beak springs
const SP_lidL  = new Spring(260,26); SP_lidL.onChange  = v => sa(bLidL,'height',Math.max(0,v));
const SP_lidR  = new Spring(260,26); SP_lidR.onChange  = v => sa(bLidR,'height',Math.max(0,v));
const SP_beak  = new Spring(240,26);
SP_beak.onChange = v => {
  const g = Math.max(0,v);
  sa(beakU, 'transform', `translate(0,${-g*0.4})`);
  sa(beakLo,'transform', `translate(0,${g*0.6})`);
};

// Opacity springs
const OSP = {};
const opEls = {
  pBrowL, pBrowR, pMouthSad,
  bBrowL, bBrowR, bMouthSad,
  yBrow,  yMouth,
  oBrowL, oBrowR,
  purpleArms, blackArms,
};
Object.entries(opEls).forEach(([k,el]) => {
  const sp = new Spring(260,28);
  sp.onChange = v => sa(el,'opacity',Math.max(0,Math.min(1,v)));
  OSP[k] = sp;
});
function fade(k,v){ OSP[k].set(v); }
function fadeAll(arr,v){ arr.forEach(k=>fade(k,v)); }
const ALL_EXPR = ['pBrowL','pBrowR','pMouthSad','bBrowL','bBrowR','bMouthSad','yBrow','yMouth','oBrowL','oBrowR'];

/* ═══════════════════════════════════════════════════
   MOUSE TRACKING — always active, no focus gating
═══════════════════════════════════════════════════ */
let mouseVB = { x: 280, y: 280 };
let trackMode = 'all'; // 'all' | 'averted-purple' | 'none'

// Convert screen coords to SVG viewBox coords
function toVB(ex, ey) {
  const r = svg.getBoundingClientRect();
  return {
    x: (ex - r.left) / r.width  * 560,
    y: (ey - r.top)  / r.height * 480,
  };
}

listen(root.ownerDocument, 'pointermove', e => {
  mouseVB = toVB(e.clientX, e.clientY);
  if (trackMode === 'all') aimAll();
  // other modes: pupils set explicitly per state
});

function aimPupil(key) {
  const p = PSP[key];
  const dx = mouseVB.x - p.ox;
  const dy = mouseVB.y - p.oy;
  const angle = Math.atan2(dy, dx);
  const dist  = Math.min(Math.hypot(dx,dy)/55, p.maxR);
  p.x.set(Math.cos(angle)*dist);
  p.y.set(Math.sin(angle)*dist);
}

function aimAll() {
  Object.keys(PSP).forEach(aimPupil);
}

function fixPupil(key, nx, ny) {
  const p = PSP[key];
  p.x.set(nx * p.maxR);
  p.y.set(ny * p.maxR);
}

function centerPupils() {
  Object.values(PSP).forEach(p => { p.x.set(0); p.y.set(0); });
}

/* ═══════════════════════════════════════════════════
   IDLE BOB — continuous float, bypasses spring
═══════════════════════════════════════════════════ */
let idleOn=false, idleT=0, idleRAF;
function startIdle() {
  if (reducedMotion) return;
  idleOn=true;
  const tick=()=>{
    if(!idleOn)return;
    idleT+=0.017;
    SPY.purple._p=Math.sin(idleT)*5.5;       applyPurple();
    SPY.black._p =Math.sin(idleT+1.1)*4.5;   sa(gBlack,'transform',`translate(0,${SPY.black._p})`);
    SPY.yellow._p=Math.sin(idleT+1.9)*4;     sa(gYellow,'transform',`translate(0,${SPY.yellow._p})`);
    SPY.orange._p=Math.sin(idleT+0.5)*3.5;   sa(gOrange,'transform',`translate(0,${SPY.orange._p})`);
    idleRAF=requestAnimationFrame(tick);
  };
  idleRAF=requestAnimationFrame(tick);
}
function stopIdle() {
  idleOn=false; cancelAnimationFrame(idleRAF);
  // Freeze velocity so springs can take over
  Object.values(SPY).forEach(sp=>{ sp._v=0; });
}

/* ═══════════════════════════════════════════════════
   BLINK SYSTEM
═══════════════════════════════════════════════════ */
// Rect overlays painted on top of each eye white
const blinkDefs=[
  {x:149,y:161,w:28,fill:'#6B4EFF'}, // purple L
  {x:219,y:161,w:28,fill:'#6B4EFF'}, // purple R
  {x:290,y:258,w:24,fill:'#1E1E1E'}, // black L
  {x:338,y:258,w:24,fill:'#1E1E1E'}, // black R
  {x:388,y:306,w:24,fill:'#F5C518'}, // yellow
];
const blinkRects = blinkDefs.map((d, blinkIndex)=>{
  const r=document.createElementNS('http://www.w3.org/2000/svg','rect');
  sa(r,'x',d.x); sa(r,'y',d.y); sa(r,'width',d.w);
  sa(r,'height',0); sa(r,'fill',d.fill); sa(r,'rx',d.w/2);
  ;[gPurple, gPurple, gBlack, gBlack, gYellow][blinkIndex].appendChild(r);
  return {el:r, maxH:d.w*0.92};
});

let blinkOk=true, blinkTmr;
function doBlink(){
  if(!blinkOk){scheduleBlink();return;}
  blinkRects.forEach(b=>{
    const sp=new Spring(700,32);
    sp.onChange=v=>sa(b.el,'height',Math.max(0,v));
    sp.snap(0); sp.set(b.maxH);
    setTimeout(()=>sp.set(0),90);
  });
  scheduleBlink();
}
function scheduleBlink(){
  if (reducedMotion) return;
  clearTimeout(blinkTmr);
  blinkTmr=setTimeout(doBlink, 2500+Math.random()*3000);
}

/* ═══════════════════════════════════════════════════
   STATE MACHINE
═══════════════════════════════════════════════════ */
let currentState='none';
let stateTimers=[];
function killTimers(){ stateTimers.forEach(t=>{clearTimeout(t);clearInterval(t);}); stateTimers=[]; svg.style.transform=''; }

function setState(s){
  if(s===currentState)return;
  killTimers(); currentState=s;
  if(s==='idle')     _idle();
  if(s==='typing')   _typing();
  if(s==='password') _password();
  if(s==='sad')      _sad();
  if(s==='success')  _success();
}

/* ── IDLE ── */
function _idle(){
  trackMode='all'; blinkOk=true;
  fadeAll(ALL_EXPR,0); fadeAll(['purpleArms','blackArms'],0);
  oMouth.setAttribute('d','M 65,385 Q 105,408 145,385');
  SP_lidL.set(0); SP_lidR.set(0); SP_beak.set(0); SPR_purple.set(0);
  // don't reset pupils — keep tracking mouse
  startIdle(); scheduleBlink();
}

/* ── TYPING (email) ── */
function _typing(){
  stopIdle(); trackMode='all'; blinkOk=true;
  fadeAll(ALL_EXPR,0); fadeAll(['purpleArms','blackArms'],0);
  oMouth.setAttribute('d','M 65,385 Q 105,408 145,385');
  SP_lidL.set(0); SP_lidR.set(0); SP_beak.set(0); SPR_purple.set(0);

  // 所有角色向前倾——"认真盯着看"
  SPY.purple.set(-8); SPY.black.set(-6); SPY.yellow.set(-5); SPY.orange.set(-4);

  // 橙色嘴巴微微上扬更多（更感兴趣的表情）
  oMouth.setAttribute('d','M 60,383 Q 105,410 150,383');

  // 黄色嘴巴张开一点（好奇）
  SP_beak.set(2.5);

  // 立刻开始追踪鼠标
  aimAll();
  scheduleBlink();

  // 入场动画：眼睛先快速放大再回弹（睁大眼睛效果）
  // 用blink rects做反向：先压扁再弹开
  blinkRects.forEach(b => {
    const sp = new Spring(500,20);
    sp.onChange = v => sa(b.el,'height', Math.max(0, v));
    sp.snap(b.maxH * 0.6);   // 先半闭
    sp.set(0);                // 快速睁开 → 显得"突然注意到"
  });
}

/* ── PASSWORD ── */
function _password(){
  stopIdle(); blinkOk=false;
  fadeAll(ALL_EXPR,0); fadeAll(['purpleArms','blackArms'],0);
  oMouth.setAttribute('d','M 65,385 Q 105,408 145,385');
  SPR_purple.set(0);
  SPY.purple.set(-4); SPY.black.set(-2); SPY.yellow.set(-2); SPY.orange.set(0);

  // ── 紫色：眼神用力往左看 (averted gaze) ──
  fixPupil('pL',-1,-0.3); fixPupil('pR',-1,-0.3);

  // ── 橙色：眼神转向左上方（背对密码框方向）──
  // 密码框在右侧，橙色本身在左边，所以往左上角看 = 彻底回避
  fixPupil('oL',-0.8,-0.7); fixPupil('oR',-0.8,-0.7);

  // ── 黄色：往右上方看（好奇地望天，假装没看见）──
  fixPupil('yP', 0.4,-1.0);

  // ── 黑色：半眼皮，眼神往左偏 ──
  SP_lidL.set(8); SP_lidR.set(8);
  fixPupil('bL',-0.9,0.2); fixPupil('bR',-0.9,0.2);

  // 嘴巴微开（紧张感）
  SP_beak.set(3.5);

  // ── 黑色 PEEK：每隔1.5~3s偷瞄一下右边 ──
  trackMode='fixed';
  function schedulePeek(){
    const t=setTimeout(()=>{
      if(currentState!=='password') return;
      // 眼皮抬起，眼珠朝右偷瞟
      SP_lidL.set(2); SP_lidR.set(2);
      fixPupil('bL',1.0,0.1); fixPupil('bR',1.0,0.1);
      const t2=setTimeout(()=>{
        if(currentState!=='password') return;
        // 缩回去
        SP_lidL.set(8); SP_lidR.set(8);
        fixPupil('bL',-0.9,0.2); fixPupil('bR',-0.9,0.2);
        schedulePeek();
      }, 380);
      stateTimers.push(t2);
    }, 1400 + Math.random()*1800);
    stateTimers.push(t);
  }
  schedulePeek();
}

/* ── SAD ── */
function _sad(){
  stopIdle(); trackMode='fixed'; blinkOk=true;
  SP_lidL.set(0); SP_lidR.set(0); SP_beak.set(0);
  fadeAll(['purpleArms','blackArms'],0);

  // Purple tilts left + sinks (match screenshot)
  SPR_purple.set(-17); SPY.purple.set(18);
  // Black droops, heavy lids
  SPY.black.set(12); SP_lidL.set(10); SP_lidR.set(10);
  // Yellow + orange sink
  SPY.yellow.set(9); SPY.orange.set(7);

  // Sad expressions
  fadeAll(['pBrowL','pBrowR','pMouthSad'],1);
  fadeAll(['bBrowL','bBrowR','bMouthSad'],1);
  fadeAll(['yBrow','yMouth'],1);
  fadeAll(['oBrowL','oBrowR'],1);
  oMouth.setAttribute('d','M 65,393 Q 105,374 145,393');

  // Pupils look down
  fixPupil('pL', 0.3, 0.85); fixPupil('pR',-0.3, 0.85);
  fixPupil('bL', 0.2, 0.7);  fixPupil('bR',-0.2, 0.7);
  fixPupil('yP', 0,   0.9);
  fixPupil('oL', 0.2, 0.6);  fixPupil('oR',-0.2, 0.6);

  // Shake
  let n=0;
  const iv=setInterval(()=>{
    n++; svg.style.transform=`translateX(${n%2===0?5:-5}px)`;
    if(n>=6){clearInterval(iv);svg.style.transform='';}
  },80);
  stateTimers.push(iv);

}

/* ── SUCCESS ── */
function _success(){
  stopIdle(); trackMode='fixed'; blinkOk=false;
  fadeAll(ALL_EXPR,0);
  SP_lidL.set(0); SP_lidR.set(0); SPR_purple.set(0);
  oMouth.setAttribute('d','M 55,382 Q 105,415 155,382');
  SP_beak.set(12);
  fadeAll(['purpleArms','blackArms'],1);
  // Pupils up (excited)
  Object.keys(PSP).forEach(k=>fixPupil(k,0,-0.9));

  const CHARS=[
    {sp:SPY.purple,d:0},{sp:SPY.black,d:80},{sp:SPY.yellow,d:155},{sp:SPY.orange,d:230}
  ];
  function oneJump(sp,delay){
    if (reducedMotion) return;
    const t1=setTimeout(()=>{
      sp.k=340;sp.b=16;sp.set(9);
      const t2=setTimeout(()=>{
        sp.set(-50);
        const t3=setTimeout(()=>{sp.k=200;sp.b=13;sp.set(0);},260);
        stateTimers.push(t3);
      },100);
      stateTimers.push(t2);
    },delay);
    stateTimers.push(t1);
  }
  CHARS.forEach(c=>oneJump(c.sp,c.d));
  const t2=setTimeout(()=>{ if(currentState==='success') CHARS.forEach(c=>oneJump(c.sp,c.d)); },700);
  stateTimers.push(t2);

  launchConfetti();

}

/* ═══════════════════════════════════════════════════
   CONFETTI
═══════════════════════════════════════════════════ */
function launchConfetti(){
  if (reducedMotion || !celebrateConfetti) return;
  const cc=$('confettiCanvas');
  const W=window.innerWidth,H=window.innerHeight;
  cc.width=W;cc.height=H;cc.style.display='block';
  const ctx=cc.getContext('2d');
  const C=['#6B4EFF','#F4782A','#F5C518','#1E1E1E','#4285F4','#34A853','#EA4335','#FF6B9D','#00CFFF'];
  const ps=Array.from({length:130},(_,i)=>({
    x:Math.random()*W,y:-20-Math.random()*80,
    w:8+Math.random()*12,h:9+Math.random()*14,
    r:Math.random()*Math.PI*2,vr:(Math.random()-0.5)*0.22,
    vx:(Math.random()-0.5)*8,vy:1.5+Math.random()*4,
    color:C[i%C.length],circ:Math.random()>.5,
    wob:Math.random()*Math.PI*2,alive:true
  }));
  (function draw(){
    ctx.clearRect(0,0,W,H);let any=false;
    ps.forEach(p=>{
      if(!p.alive)return;
      p.wob+=0.055;p.x+=p.vx+Math.sin(p.wob)*1.5;
      p.y+=p.vy;p.r+=p.vr;p.vy+=0.072;
      if(p.y>H+40){p.alive=false;return;}
      any=true;
      ctx.save();ctx.translate(p.x,p.y);ctx.rotate(p.r);
      ctx.fillStyle=p.color;ctx.globalAlpha=Math.min(1,(H-p.y+60)/65);
      if(p.circ){ctx.beginPath();ctx.arc(0,0,p.w/2,0,Math.PI*2);ctx.fill();}
      else ctx.fillRect(-p.w/2,-p.h/2,p.w,p.h);
      ctx.restore();
    });
    if(any)requestAnimationFrame(draw);
    else{ctx.clearRect(0,0,W,H);cc.style.display='none';}
  })();
}


  setState('idle')
  return {
    setState(state) { if (!disposed) setState(state) },
    dispose() {
      disposed = true
      frames.forEach(id => window.cancelAnimationFrame(id))
      timeouts.forEach(id => window.clearTimeout(id))
      intervals.forEach(id => window.clearInterval(id))
      listeners.forEach(remove => remove())
      blinkRects.forEach(({ el }) => el.remove())
      svg.style.transform = ''
      $('confettiCanvas').style.display = 'none'
      frames.clear(); timeouts.clear(); intervals.clear()
    },
  }
}

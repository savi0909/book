import assert from 'node:assert/strict';
import fs from 'node:fs';
import {execFileSync} from 'node:child_process';
if (!fs.existsSync('compose.poison.yml') || process.argv.length!==2) throw new Error('Run from ticket-booking-lab, without arguments');
const A='http://localhost:8105',B='http://localhost:8106';
const compose=['compose','-f','compose.yml','-f','compose.failover.yml','-f','compose.poison.yml'];
const evidence={startedAt:new Date().toISOString(),checks:[],fixtures:[],observations:[]};
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
function check(name,value){assert.ok(value,name);evidence.checks.push(name);console.log('PASS '+name);}
async function call(base,path,body,key){
 const r=await fetch(base+path,{method:body===undefined?'GET':'POST',headers:{'Content-Type':'application/json',...(key?{'Idempotency-Key':key}:{})},body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(8000)});
 return {status:r.status,data:await r.json()};
}
async function ok(base,path,body,key){const r=await call(base,path,body,key);assert.ok(r.status>=200&&r.status<300,JSON.stringify(r));return r.data;}
async function state(id){return ok(B,`/api/payments/${id}/recovery`);}
async function ready(){for(let i=0;i<100;i++){try{if((await call(A,'/actuator/health/readiness')).status===200)return;}catch{}await sleep(300);}throw new Error('A not ready');}
async function intent(event,seat,ttl=120){
 const b=await ok(A,'/api/holds',{eventId:event.id,seatNumber:seat,buyerId:'p-'+crypto.randomUUID(),ttlSeconds:ttl},'h-'+crypto.randomUUID());
 return ok(A,`/api/bookings/${b.id}/checkout`,{scenario:'SUCCESS',delayMs:0},'c-'+b.id);
}
const fixture=(id,enabled)=>ok(A,`/api/demo/recovery/payments/${id}/fixture`,{enabled});
const tick=()=>ok(B,'/api/demo/recovery/tick',{});
const redrive=(id,key,base=B)=>call(base,`/api/demo/recovery/payments/${id}/redrive`,{},key);
async function quarantine(id){for(let i=0;i<3;i++){await tick();if(i<2)await sleep(1100);}check('three item failures quarantine '+id,(await state(id)).itemFailures===3);}
let paused=false;
try {
 check('manual recovery topology',!(await ok(A,'/api/demo/recovery/controls')).maintenanceEnabled&&!(await ok(B,'/api/demo/recovery/controls')).maintenanceEnabled);
 check('default provider avoids occupied8123',!(await ok(A,'/api/provider/status')).boundary.enabled);
 const e=await ok(A,'/api/demo/events',{name:'poison-'+crypto.randomUUID(),seatCount:6});evidence.fixtures.push(e.id);
 const bad=await intent(e,1);await fixture(bad.payment.id,true);
 const healthy=[];for(let i=2;i<=5;i++)healthy.push(await intent(e,i));
 await tick();
 check('first failed item does not skip four healthy payments',(await Promise.all(healthy.map(b=>ok(B,`/api/bookings/${b.id}`)))).every(b=>b.state==='CONFIRMED'));
 check('first failure preserves UNKNOWN',(await state(bad.payment.id)).state==='UNKNOWN');
 await sleep(1100);await tick();await sleep(1100);await tick();
 const q=await state(bad.payment.id);evidence.observations.push({quarantine:q});
 check('exactly three attempts quarantined',q.attempts===3&&q.quarantinedAt!==null&&q.quarantineReason==='PoisonFixtureException');
 await sleep(1100);await tick();check('quarantine stops automatic dispatch',(await state(bad.payment.id)).attempts===3);
 check('ordinary reconcile cannot bypass quarantine',(await call(B,`/api/demo/payments/${bad.payment.id}/reconcile`,{})).status===409);
 execFileSync('docker',[...compose,'restart','api-a'],{stdio:'pipe'});await ready();
 check('quarantine survives API restart',(await ok(A,`/api/payments/${bad.payment.id}/recovery`)).quarantinedAt!==null);
 await fixture(bad.payment.id,false);
 const replies=await Promise.all([redrive(bad.payment.id,'same/'+bad.payment.id,A),redrive(bad.payment.id,'same/'+bad.payment.id,B)]);
 check('cross-replica duplicate redrive dispatched once',replies.every(r=>r.status===200)&&replies.filter(r=>r.data.replayed).length===1);
 const fixed=await ok(B,`/api/bookings/${bad.id}`);
 check('fixed redrive confirms original identity',fixed.state==='CONFIRMED'&&fixed.payment.id===bad.payment.id&&(await state(bad.payment.id)).attempts===4);
 check('one confirmation audit',(await ok(B,`/api/bookings/${bad.id}/audit`)).filter(x=>x.action==='CONFIRMED').length===1);
 const late=await intent(e,6,2);await fixture(late.payment.id,true);await quarantine(late.payment.id);
 const replacement=await ok(B,'/api/holds',{eventId:e.id,seatNumber:6,buyerId:'replacement-'+crypto.randomUUID(),ttlSeconds:120},'h-'+crypto.randomUUID());
 await fixture(late.payment.id,false);assert.equal((await redrive(late.payment.id,'late/'+late.payment.id)).status,200);
 const old=await ok(B,`/api/bookings/${late.id}`);
 check('late success requires refund',old.state==='EXPIRED'&&old.reconciliation==='REFUND_REQUIRED');
 check('replacement owner stays HELD',(await ok(B,`/api/bookings/${replacement.id}`)).state==='HELD');
 const limitEvent=await ok(A,'/api/demo/events',{name:'redrive-limit-'+crypto.randomUUID(),seatCount:1});evidence.fixtures.push(limitEvent.id);
 const limited=await intent(limitEvent,1);await fixture(limited.payment.id,true);await quarantine(limited.payment.id);
 assert.equal((await redrive(limited.payment.id,'r1')).status,200);assert.equal((await redrive(limited.payment.id,'r2')).status,200);
 check('third redrive denied',(await redrive(limited.payment.id,'r3')).status===409);
 check('broken redrives bounded at five total attempts',(await state(limited.payment.id)).attempts===5);
 check('same key replay remains discoverable',(await redrive(limited.payment.id,'r2')).data.replayed===true);
 await fixture(limited.payment.id,false);
 await ok(B,`/api/demo/payments/${limited.payment.id}/callback`,{eventId:'verified/'+limited.payment.id,outcome:'SUCCESS'});
 check('verified callback resolves exhausted quarantine',(await state(limited.payment.id)).quarantinedAt===null);
 const history=await ok(B,`/api/payments/${limited.payment.id}/recovery/history`);evidence.observations.push({history});
 check('durable failure and redrive history',history.filter(x=>x.action==='REDRIVE_REQUESTED').length===2&&history.some(x=>x.action==='QUARANTINED'));
 execFileSync('docker',[...compose,'pause','postgres'],{stdio:'pipe'});paused=true;
 const outage=await call(B,'/api/demo/recovery/tick',{});check('shared DB outage returns503',outage.status===503);
 execFileSync('docker',[...compose,'unpause','postgres'],{stdio:'pipe'});paused=false;
 check('DB outage created no false quarantine',Number((await ok(B,'/api/recovery/status')).quarantined)===0);
 check('gateway business read works',(await call('http://localhost:8107',`/api/bookings/${bad.id}`)).status===200);
 evidence.restored=true;
} finally {
 if(paused)execFileSync('docker',[...compose,'unpause','postgres'],{stdio:'pipe'});
 evidence.finishedAt=new Date().toISOString();fs.mkdirSync('target',{recursive:true});fs.writeFileSync('target/poison-runtime-evidence.json',JSON.stringify(evidence,null,2));
}
console.log(`${evidence.checks.length} checks passed; data retained`);

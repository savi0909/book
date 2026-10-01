import assert from 'node:assert/strict';
import fs from 'node:fs';
import {execFileSync} from 'node:child_process';
if(!fs.existsSync('compose.outbox.yml') || process.argv.length!==2)throw new Error('Run from ticket-booking-lab without arguments');
const A='http://localhost:8105',B='http://localhost:8106',G='http://localhost:8107';
const compose=['compose','-f','compose.yml','-f','compose.failover.yml','-f','compose.outbox.yml'];
const evidence={startedAt:new Date().toISOString(),checks:[],fixtures:[],observations:[]};
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
function check(name,value){assert.ok(value,name);evidence.checks.push(name);console.log('PASS '+name);}
async function call(base,path,body,key){
 const r=await fetch(base+path,{method:body===undefined?'GET':'POST',headers:{'Content-Type':'application/json',...(key?{'Idempotency-Key':key}:{})},body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(15000)});
 const text=await r.text();let data;try{data=JSON.parse(text);}catch{data=text;}return {status:r.status,data};
}
async function ok(base,path,body,key){const r=await call(base,path,body,key);assert.ok(r.status>=200&&r.status<300,JSON.stringify(r));return r.data;}
async function until(name,operation,predicate,seconds=15){
 const end=Date.now()+seconds*1000;let result;
 do{try{result=await operation();}catch(error){result={error:error.message};}if(predicate(result)){check(name,true);return result;}await sleep(150);}while(Date.now()<end);
 throw new Error(name+': '+JSON.stringify(result));
}
async function confirmed(){
 const e=await ok(A,'/api/demo/events',{name:'outbox-'+crypto.randomUUID(),seatCount:2});evidence.fixtures.push(e.id);
 const b=await ok(A,'/api/holds',{eventId:e.id,seatNumber:1,buyerId:'b-'+crypto.randomUUID(),ttlSeconds:120},'h-'+crypto.randomUUID());
 const checkout=await ok(A,`/api/bookings/${b.id}/checkout`,{scenario:'SUCCESS',delayMs:0},'c-'+b.id);
 const done=await ok(A,`/api/demo/payments/${checkout.payment.id}/reconcile`,{});assert.equal(done.state,'CONFIRMED');return done;
}
const delivery=id=>ok(B,`/api/bookings/${id}/delivery`);
const dispatch=(id,base=B)=>ok(base,`/api/demo/outbox/events/${id}/dispatch`,{});
const delay=(id,delayMs)=>ok(B,`/api/demo/outbox/events/${id}/delay`,{delayMs});
let restored=false;
try {
 check('both dispatch schedulers disabled',!(await ok(A,'/api/demo/outbox/controls')).automaticDispatch&&!(await ok(B,'/api/demo/outbox/controls')).automaticDispatch);
 check('default simulator, other provider port untouched',!(await ok(A,'/api/provider/status')).boundary.enabled);
 const first=await confirmed();const initial=await delivery(first.id);const firstId=initial.events[0].id;
 check('committed confirmation has durable pending event',initial.events.length===1&&initial.events[0].deliveredAt===null&&initial.events[0].kind==='CONFIRMED'&&initial.events[0].bookingVersion===1);
 check('nothing consumed before dispatch',initial.inbox.length===0&&initial.notifications.length===0);
 execFileSync('docker',[...compose,'kill','-s','SIGKILL','api-a'],{stdio:'pipe'});
 check('B sees confirmation and work after A crash',(await ok(B,`/api/bookings/${first.id}`)).state==='CONFIRMED'&&(await delivery(first.id)).events[0].id===firstId);
 await dispatch(firstId);const recovered=await delivery(first.id);
 check('B delivers committed event after crash',recovered.notifications.length===1&&recovered.events[0].deliveredAt!==null);
 execFileSync('docker',[...compose,'up','-d','--wait','api-a'],{stdio:'pipe'});
 await until('A restored',()=>call(A,'/actuator/health/readiness'),r=>r.status===200,30);
 const second=await confirmed();const secondId=(await delivery(second.id)).events[0].id;await delay(secondId,10000);
 const lost=dispatch(secondId,A).then(r=>({response:r}),error=>({error:error.message}));
 await until('consumer committed before dispatcher ack',()=>ok(A,'/api/demo/outbox/controls'),r=>r.waitingEvents.includes(secondId));
 const gap=await delivery(second.id);evidence.observations.push({ackGap:gap});
 check('inbox and receipt durable while outbox pending',gap.inbox.length===1&&gap.notifications.length===1&&gap.events[0].deliveredAt===null);
 check('active lease rejects another dispatcher',!(await dispatch(secondId)).claimed);
 check('booking DB remains usable during post-consume delay',(await ok(B,'/api/holds',{eventId:second.eventId,seatNumber:2,buyerId:'healthy-'+crypto.randomUUID(),ttlSeconds:120},'h-'+crypto.randomUUID())).state==='HELD');
 execFileSync('docker',[...compose,'kill','-s','SIGKILL','api-a'],{stdio:'pipe'});const uncertain=await lost;
 check('actual kill loses dispatch HTTP response',Boolean(uncertain.error));
 await delay(secondId,0);
 await until('B reclaims event after lease expiry',()=>dispatch(secondId),r=>r.claimed,12);
 const duplicated=await delivery(second.id);evidence.observations.push({redelivery:duplicated});
 check('duplicate consumed twice but local effect once',duplicated.events[0].attempts===2&&duplicated.inbox[0].deliveries===2&&duplicated.notifications.length===1);
 check('recovered event acknowledged',duplicated.events[0].deliveredAt!==null);
 check('acknowledged event is not dispatched again',!(await dispatch(secondId)).claimed);
 execFileSync('docker',[...compose,'up','-d','--wait','api-a'],{stdio:'pipe'});
 await until('A restored after second crash',()=>call(A,'/actuator/health/readiness'),r=>r.status===200,30);
 const third=await confirmed();const thirdId=(await delivery(third.id)).events[0].id;
 const race=await Promise.all([dispatch(thirdId,A),dispatch(thirdId,B)]);
 check('two replicas claim same event only once',race.filter(r=>r.claimed).length===1);
 check('one local receipt after replica race',(await delivery(third.id)).notifications.length===1);
 const ordered=await confirmed();await ok(B,`/api/bookings/${ordered.id}/cancel`,{});
 const events=(await delivery(ordered.id)).events;
 check('confirmation and cancellation snapshots have increasing versions',events.length===2&&events[0].kind==='CONFIRMED'&&events[1].kind==='CANCELLED'&&events[1].bookingVersion===2);
 await ok(B,`/api/demo/outbox/events/${events[1].id}/consume`,{});
 const stale=await ok(A,`/api/demo/outbox/events/${events[0].id}/consume`,{});
 check('old confirmation ignored after cancellation',stale.disposition==='STALE_IGNORED');
 const projection=await delivery(ordered.id);evidence.observations.push({outOfOrder:projection});
 check('projection remains cancelled at version2',projection.projection[0].state==='CANCELLED'&&projection.projection[0].bookingVersion===2);
 check('stale confirmation produces no local notification',projection.notifications.length===1&&projection.notifications[0].kind==='CANCELLED');
 for(const event of events)await dispatch(event.id);
 await ok(B,'/api/demo/outbox/tick',{});
 check('backlog drained',Number((await ok(B,'/api/outbox/status')).pending)===0);
 check('gateway delivery diagnostics work',(await call(G,`/api/bookings/${ordered.id}/delivery`)).status===200);
 check('gateway excludes replica controls',(await call(G,'/api/demo/outbox/tick',{})).status===404);
 restored=true;
} finally {
 execFileSync('docker',[...compose,'up','-d','--wait','api-a','api-b','gateway'],{stdio:'pipe'});
 evidence.restored=restored;evidence.finishedAt=new Date().toISOString();fs.mkdirSync('target',{recursive:true});fs.writeFileSync('target/outbox-runtime-evidence.json',JSON.stringify(evidence,null,2));
}
console.log(`${evidence.checks.length} checks passed; data retained`);

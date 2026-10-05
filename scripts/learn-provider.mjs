import assert from 'node:assert/strict';
import fs from 'node:fs';
import {execFileSync} from 'node:child_process';
if(!fs.existsSync('compose.provider.yml') || !fs.existsSync('src/main/java/com/example/booking/ProviderBoundary.java') || process.argv.length!==2)
  throw new Error('Run node scripts/learn-provider.mjs from sd-book-my-show; no arguments supported');
const A='http://localhost:8130',B='http://localhost:8131',P='http://localhost:8133';
const evidence={startedAt:new Date().toISOString(),checks:[],fixtures:[],observations:[]};
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
function check(name,condition){assert.ok(condition,name);evidence.checks.push(name);console.log('PASS '+name);}
async function call(base,path,body,key){
  const response=await fetch(base+path,{method:body===undefined?'GET':'POST',headers:{'Content-Type':'application/json',...(key?{'Idempotency-Key':key}:{})},body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(6000)});
  const text=await response.text();let data;try{data=JSON.parse(text);}catch{data=text;}
  return {status:response.status,data};
}
async function mode(value){const r=await fetch(P+'/control?'+value,{method:'POST'});assert.equal(r.status,200);}
async function until(name,operation,predicate,seconds=20){
  const end=Date.now()+seconds*1000;let last;
  do{try{last=await operation();}catch(error){last={error:error.message};}if(predicate(last)){check(name,true);return last;}await sleep(150);}while(Date.now()<end);
  throw new Error(name+': '+JSON.stringify(last));
}
async function fixture(seats=4){
  const event=await call(A,'/api/demo/events',{name:'provider-'+crypto.randomUUID(),seatCount:seats});assert.equal(event.status,201);
  evidence.fixtures.push(event.data.id);return event.data;
}
async function hold(event,seat,ttl=120){const r=await call(B,'/api/holds',{eventId:event.id,seatNumber:seat,buyerId:'p-'+crypto.randomUUID(),ttlSeconds:ttl},'h-'+crypto.randomUUID());assert.equal(r.status,201);return r.data;}
async function checkout(b){const r=await call(A,`/api/bookings/${b.id}/checkout`,{scenario:'SUCCESS',delayMs:0},'c-'+b.id);assert.equal(r.status,202);return r.data;}
async function get(b){return (await call(B,'/api/bookings/'+b.id)).data;}
async function recover(b){return call(B,`/api/demo/payments/${b.payment.id}/reconcile`,{});}
const compose=['compose','-f','compose.yml','-f','compose.failover.yml','-f','compose.provider.yml'];
try {
  await mode('NORMAL');
  const status=(await call(A,'/api/provider/status')).data;check('independent provider enabled',status.boundary.enabled);
  await until('initial backlog drained',async()=>(await call(A,'/api/provider/status')).data,x=>Number(x.backlog.unresolved)===0);
  await mode('SLOW');const e=await fixture();const first=await checkout(await hold(e,1)),second=await checkout(await hold(e,2));
  const requests=Promise.all([recover(first),recover(second)]);
  await until('remote work in flight',async()=>(await call(P,'/stats')).data,x=>x.active>0,5);
  const free=await hold(e,3);check('hold works during slow provider',free.state==='HELD');
  check('browse works during slow provider',(await call(A,`/api/events/${e.id}/seats`)).status===200);
  await requests;await sleep(450);
  const remote=(await call(P,'/stats')).data;evidence.observations.push({slowRemote:remote,apiA:(await call(A,'/api/provider/status')).data});
  check('remote continuation survives caller deadline',remote.active>0);
  check('provider processing never exceeds two slots',remote.maximum<=2);
  check('ambiguous result stays discoverable',['PENDING','UNKNOWN'].includes((await get(first)).payment.state));
  await mode('NORMAL');await sleep(1700);
  await until('slow payments drain with same IDs',async()=>{await recover(first);await recover(second);return [await get(first),await get(second)];},x=>x.every(b=>b.payment.state==='SUCCESS'));
  check('stable first payment identity',(await get(first)).payment.id===first.payment.id);
  await mode('UNAVAILABLE');const outage=await checkout(await hold(await fixture(),1));
  const exhausted=await until('automatic budget exhausted',async()=>(await call(A,`/api/payments/${outage.payment.id}/recovery`)).data,x=>x.retryExhausted,18);
  evidence.observations.push({exhausted});check('automatic attempts bounded',exhausted.attempts<=4);
  check('unavailable provider remains UNKNOWN',(await get(outage)).payment.state==='UNKNOWN');
  await sleep(2300);const stopped=(await call(A,`/api/payments/${outage.payment.id}/recovery`)).data;check('automatic polling does not reset budget',stopped.attempts===exhausted.attempts);
  check('browse survives outage',(await call(B,'/api/events')).status===200);
  await mode('NORMAL');await sleep(2200);
  await until('operator reconciles exhausted payment',async()=>{await recover(outage);return get(outage);},x=>x.payment.state==='SUCCESS');
  await mode('LOSS');const lateEvent=await fixture();const old=await checkout(await hold(lateEvent,1,2));
  await until('durable acceptance despite lost response',async()=>execFileSync('docker',[...compose,'exec','-T','provider','cat','/data/receipts.log'],{encoding:'utf8'}),x=>typeof x==='string' && x.includes(old.payment.id),8);
  await sleep(2100);const replacement=await hold(lateEvent,1);check('expired seat gets a new owner',replacement.id!==old.id);
  await mode('NORMAL');await sleep(2200);
  await until('late success reaches refund reconciliation',async()=>{await recover(old);return get(old);},x=>x.reconciliation==='REFUND_REQUIRED');
  check('late payment does not steal seat',(await get(replacement)).state==='HELD');
  const receiptBefore=(await call(P,'/stats')).data.receipts;
  execFileSync('docker',[...compose,'restart','provider'],{stdio:'pipe'});
  await until('provider restarts with retained receipts',async()=>(await call(P,'/stats')).data,x=>x.receipts===receiptBefore,20);
  const final=(await call(A,'/api/provider/status')).data;evidence.observations.push({drained:final});
  check('backlog drained',Number(final.backlog.unresolved)===0);
} finally {
  try{await mode('NORMAL');evidence.restored=true;}catch{evidence.restored=false;}
  evidence.finishedAt=new Date().toISOString();fs.writeFileSync('target/provider-runtime-evidence.json',JSON.stringify(evidence,null,2));
}
console.log(JSON.stringify({checks:evidence.checks.length,restored:evidence.restored}));

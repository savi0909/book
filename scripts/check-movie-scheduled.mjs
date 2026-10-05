import assert from 'node:assert/strict';
import fs from 'node:fs';

const source=JSON.parse(fs.readFileSync('target/movie-runtime-evidence.json','utf8'));
const showId=source.fixtures.find(f=>f.showId).showId;
const run=crypto.randomUUID(),checks=[];
async function call(path,body,key) {
  const response=await fetch('http://localhost:8130'+path,{method:body===undefined?'GET':'POST',headers:{'Content-Type':'application/json',...(key?{'Idempotency-Key':key}:{})},body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(10000)});
  const text=await response.text();let data;try{data=JSON.parse(text);}catch{data=text;}return {status:response.status,data};
}
function check(name,condition) {assert.ok(condition,name);checks.push(name);}
const held=await call('/api/movie/holds',{showId,seatNumbers:[4,5],buyerId:'scheduled-'+run,ttlSeconds:900},'scheduled-hold-'+run);
check('New scheduled-mode group admitted',held.status===201);
const bookingId=held.data.id;
const disabled=await call(`/api/movie/bookings/${bookingId}/checkout`,{testBucket:0},'disabled-'+run);
check('Deterministic fixture input disabled',disabled.status===400&&disabled.data.code==='PAYMENT_FIXTURES_DISABLED');
const checkout=await call(`/api/movie/bookings/${bookingId}/checkout`,{},'scheduled-pay-'+run);
check('Ordinary random-plan checkout accepted',checkout.status===202);
const paymentId=checkout.data.payment.id;
const manual=await call(`/api/demo/movie/payments/${paymentId}/reconcile`,{});
check('Manual movie reconcile disabled',manual.status===404);
let result;
const deadline=Date.now()+15000;
do {
  result=await call(`/api/movie/bookings/${bookingId}`);
  if(['CONFIRMED','HELD','EXPIRED'].includes(result.data.state)&&['SUCCEEDED','FAILED'].includes(result.data.payment?.state)) break;
  await new Promise(resolve=>setTimeout(resolve,150));
} while(Date.now()<deadline);
check('Scheduled worker resolves ordinary payment',result.data.payment?.state==='SUCCEEDED'||result.data.payment?.state==='FAILED');
check('Default outcome matches group transition',result.data.payment.state==='SUCCEEDED'?result.data.state==='CONFIRMED':result.data.state==='HELD');
const evidence={checkedAt:new Date().toISOString(),bookingId,paymentId,checks,result:result.data};
fs.writeFileSync('target/movie-scheduled-evidence.json',JSON.stringify(evidence,null,2)+'\n');
console.log(JSON.stringify({checks:checks.length,bookingId,state:result.data.state,paymentState:result.data.payment.state}));

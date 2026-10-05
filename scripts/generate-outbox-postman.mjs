import fs from 'node:fs';
const items=[];
function req(name,method,path,body,test,base='{{baseUrl}}',key){
 const header=[{key:'Content-Type',value:'application/json'}];if(key)header.push({key:'Idempotency-Key',value:key});
 items.push({name,request:{method,header,url:base+path,...(body===undefined?{}:{body:{mode:'raw',raw:JSON.stringify(body),options:{raw:{language:'json'}}}})},event:[{listen:'test',script:{type:'text/javascript',exec:test.split('\n')}}]});
}
const status=n=>`pm.test('HTTP ${n}',()=>pm.response.to.have.status(${n}));`;
req('01 Manual outbox topology','GET','/api/demo/outbox/controls',undefined,status(200)+"pm.test('automatic dispatcher disabled',()=>pm.expect(pm.response.json().automaticDispatch).eql(false));");
req('02 Fresh retained event','POST','/api/demo/events',{name:'outbox-{{$guid}}',seatCount:2},status(201)+"pm.environment.set('eventId',pm.response.json().id);pm.environment.set('runKey',pm.variables.replaceIn('{{$guid}}'));['first','second','paymentId','outboxId','confirmId','cancelId'].forEach(k=>pm.environment.unset(k));");
for(const [tag,seat] of [['first',1],['second',2]]){
 req(`Hold ${tag}`,'POST','/api/holds',{eventId:'{{eventId}}',seatNumber:seat,buyerId:tag+'-{{runKey}}',ttlSeconds:120},status(201)+`pm.environment.set('${tag}',pm.response.json().id);`,'{{baseUrl}}','h-'+tag+'-{{runKey}}');
 req(`Checkout ${tag}`,'POST',`/api/bookings/{{${tag}}}/checkout`,{scenario:'SUCCESS',delayMs:0},status(202)+"pm.environment.set('paymentId',pm.response.json().payment.id);",'{{baseUrl}}','c-'+tag+'-{{runKey}}');
 req(`Confirm ${tag}`,'POST','/api/demo/payments/{{paymentId}}/reconcile',{},status(200)+"pm.test('source confirmed',()=>pm.expect(pm.response.json().state).eql('CONFIRMED'));");
 if(tag==='first'){
  req('Durable pending confirmation','GET','/api/bookings/{{first}}/delivery',undefined,status(200)+"pm.test('commit includes pending event',()=>{const d=pm.response.json();pm.expect(d.events.length).eql(1);pm.expect(d.events[0].kind).eql('CONFIRMED');pm.expect(d.events[0].bookingVersion).eql(1);pm.expect(d.events[0].deliveredAt).eql(null);pm.expect(d.notifications.length).eql(0);});pm.environment.set('outboxId',pm.response.json().events[0].id);");
  req('Bounded post-consume delay','POST','/api/demo/outbox/events/{{outboxId}}/delay',{delayMs:50},status(200));
  req('B dispatches original event','POST','/api/demo/outbox/events/{{outboxId}}/dispatch',{},status(200)+"pm.test('one claim',()=>pm.expect(pm.response.json().claimed).eql(true));",'{{apiB}}');
  req('Already acknowledged event skips','POST','/api/demo/outbox/events/{{outboxId}}/dispatch',{},status(200)+"pm.test('no additional attempt',()=>pm.expect(pm.response.json().claimed).eql(false));");
  req('Duplicate consumer delivery','POST','/api/demo/outbox/events/{{outboxId}}/consume',{},status(200)+"pm.test('inbox deduplication',()=>pm.expect(pm.response.json().replayed).eql(true));");
  req('One local receipt after duplicate','GET','/api/bookings/{{first}}/delivery',undefined,status(200)+"pm.test('effect once, deliveries twice',()=>{const d=pm.response.json();pm.expect(d.notifications.length).eql(1);pm.expect(d.inbox[0].deliveries).eql(2);pm.expect(d.events[0].attempts).eql(1);pm.expect(d.events[0].deliveredAt).to.be.a('string');pm.expect(d.projection[0].state).eql('CONFIRMED');});");
 }
}
req('Cancel second before delivery','POST','/api/bookings/{{second}}/cancel',{},status(200)+"pm.test('source cancelled',()=>pm.expect(pm.response.json().state).eql('CANCELLED'));");
req('Cancellation has next version','GET','/api/bookings/{{second}}/delivery',undefined,status(200)+"pm.test('two immutable snapshots',()=>{const e=pm.response.json().events;pm.expect(e.length).eql(2);pm.expect(e[0].bookingVersion).eql(1);pm.expect(e[1].bookingVersion).eql(2);pm.expect(e[1].kind).eql('CANCELLED');});const e=pm.response.json().events;pm.environment.set('confirmId',e[0].id);pm.environment.set('cancelId',e[1].id);");
req('Deliver cancellation first','POST','/api/demo/outbox/events/{{cancelId}}/consume',{},status(200)+"pm.test('newest snapshot applied',()=>pm.expect(pm.response.json().disposition).eql('APPLIED'));");
req('Older confirmation ignored','POST','/api/demo/outbox/events/{{confirmId}}/consume',{},status(200)+"pm.test('out-of-order stale event ignored',()=>pm.expect(pm.response.json().disposition).eql('STALE_IGNORED'));");
req('Projection never reactivates','GET','/api/bookings/{{second}}/delivery',undefined,status(200)+"pm.test('cancelled projection, no stale notification',()=>{const d=pm.response.json();pm.expect(d.projection[0].state).eql('CANCELLED');pm.expect(d.projection[0].bookingVersion).eql(2);pm.expect(d.notifications.length).eql(1);pm.expect(d.notifications[0].kind).eql('CANCELLED');});");
req('Recovery batch acknowledges both events','POST','/api/demo/outbox/tick',{},status(200));
req('Acknowledgements preserve projection','GET','/api/bookings/{{second}}/delivery',undefined,status(200)+"pm.test('both acknowledged, effect unchanged',()=>{const d=pm.response.json();pm.expect(d.events.every(e=>e.deliveredAt!==null)).eql(true);pm.expect(d.notifications.length).eql(1);pm.expect(d.projection[0].state).eql('CANCELLED');});");
req('Outbox count and age visible','GET','/api/outbox/status',undefined,status(200)+"pm.test('operational metadata',()=>{const r=pm.response.json();pm.expect(Number(r.pending)).to.be.at.least(0);pm.expect(Number(r.oldestSeconds)).to.be.at.least(0);});");
req('Delay over bound rejected','POST','/api/demo/outbox/events/{{outboxId}}/delay',{delayMs:10001},status(400));
req('Missing delay rejected','POST','/api/demo/outbox/events/{{outboxId}}/delay',{},status(400));
req('Unknown event rejected','POST','/api/demo/outbox/events/{{$guid}}/dispatch',{},status(404));
req('Replica controls excluded at gateway','POST','/api/demo/outbox/tick',{},status(404),'{{gatewayUrl}}');
fs.writeFileSync('postman/outbox.postman_collection.json',JSON.stringify({info:{name:'Ticket scenario 4: transactional outbox and inbox',description:'Enable base+failover+outbox overlay; both maintenance/dispatch loops OFF. Local retained fixtures, no sends/deletion/reset. Run sequentially. The runtime harness additionally proves actual API crashes at both commit gaps.',schema:'https://schema.getpostman.com/json/collection/v2.1.0/collection.json'},item:items},null,2));
fs.writeFileSync('postman/outbox.postman_environment.json',JSON.stringify({name:'Ticket outbox local',values:[['baseUrl','http://localhost:8130'],['apiB','http://localhost:8131'],['gatewayUrl','http://localhost:8132']].map(([key,value])=>({key,value,enabled:true})),_postman_variable_scope:'environment'},null,2));

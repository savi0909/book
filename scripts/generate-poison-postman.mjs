import fs from 'node:fs';
const items=[];
function req(name,method,path,body,test,key,delay=false){
 const header=[{key:'Content-Type',value:'application/json'}];if(key)header.push({key:'Idempotency-Key',value:key});
 const event=[{listen:'test',script:{type:'text/javascript',exec:test.split('\n')}}];
 if(delay)event.unshift({listen:'prerequest',script:{type:'text/javascript',exec:['setTimeout(() => {}, 1100);']}});
 items.push({name,request:{method,header,url:'{{baseUrl}}'+path,...(body===undefined?{}:{body:{mode:'raw',raw:JSON.stringify(body),options:{raw:{language:'json'}}}})},event});
}
const status=n=>`pm.test('HTTP ${n}',()=>pm.response.to.have.status(${n}));`;
req('01 Explicit manual topology','GET','/api/demo/recovery/controls',undefined,status(200)+"pm.test('automatic maintenance off',()=>pm.expect(pm.response.json().maintenanceEnabled).eql(false));");
req('02 Fresh retained event','POST','/api/demo/events',{name:'poison-{{$guid}}',seatCount:2},status(201)+"pm.environment.set('eventId',pm.response.json().id);pm.environment.set('runKey',pm.variables.replaceIn('{{$guid}}'));['bad','good','paymentId'].forEach(k=>pm.environment.unset(k));");
for(const [tag,seat] of [['bad',1],['good',2]]){
 req(`Hold ${tag}`,'POST','/api/holds',{eventId:'{{eventId}}',seatNumber:seat,buyerId:tag+'-{{runKey}}',ttlSeconds:120},status(201)+`pm.environment.set('${tag}',pm.response.json().id);`,'h-'+tag+'-{{runKey}}');
 req(`Checkout ${tag}`,'POST',`/api/bookings/{{${tag}}}/checkout`,{scenario:'SUCCESS',delayMs:0},status(202)+(tag==='bad'?"pm.environment.set('paymentId',pm.response.json().payment.id);":''),'c-'+tag+'-{{runKey}}');
 if(tag==='bad')req('Enable deterministic after-accept fault','POST','/api/demo/recovery/payments/{{paymentId}}/fixture',{enabled:true},status(200));
}
req('First batch continues','POST','/api/demo/recovery/tick',{},status(200));
req('Healthy booking confirmed','GET','/api/bookings/{{good}}',undefined,status(200)+"pm.test('healthy item progressed',()=>pm.expect(pm.response.json().state).eql('CONFIRMED'));");
req('First failure is UNKNOWN','GET','/api/payments/{{paymentId}}/recovery',undefined,status(200)+"pm.test('isolated failure deferred',()=>{const r=pm.response.json();pm.expect(r.itemFailures).eql(1);pm.expect(r.state).eql('UNKNOWN');pm.expect(r.quarantinedAt).eql(null);});");
for(let i=2;i<=3;i++)req(`Retry batch ${i}`,'POST','/api/demo/recovery/tick',{},status(200),undefined,true);
req('Quarantine visible','GET','/api/payments/{{paymentId}}/recovery',undefined,status(200)+"pm.test('bounded three attempts',()=>{const r=pm.response.json();pm.expect(r.attempts).eql(3);pm.expect(r.itemFailures).eql(3);pm.expect(r.quarantinedAt).to.be.a('string');pm.expect(r.quarantineReason).eql('PoisonFixtureException');});");
req('Quarantine batch skips payment','POST','/api/demo/recovery/tick',{},status(200),undefined,true);
req('Ordinary reconcile denied','POST','/api/demo/payments/{{paymentId}}/reconcile',{},status(409)+"pm.test('deliberate redrive required',()=>pm.expect(pm.response.json().code).eql('PAYMENT_QUARANTINED'));");
req('History is durable','GET','/api/payments/{{paymentId}}/recovery/history',undefined,status(200)+"pm.test('quarantine action retained',()=>pm.expect(pm.response.json().some(x=>x.action==='QUARANTINED')).eql(true));");
req('Quarantine aggregate','GET','/api/recovery/status',undefined,status(200)+"pm.test('quarantine counted',()=>pm.expect(Number(pm.response.json().quarantined)).to.be.at.least(1));");
req('Fix fixture cause','POST','/api/demo/recovery/payments/{{paymentId}}/fixture',{enabled:false},status(200));
req('Keyed redrive original payment','POST','/api/demo/recovery/payments/{{paymentId}}/redrive',{},status(200)+"pm.test('one bounded redrive applies',()=>{const r=pm.response.json();pm.expect(r.replayed).eql(false);pm.expect(r.recovery.state).eql('SUCCESS');pm.expect(r.recovery.attempts).eql(4);pm.expect(r.recovery.redriveCount).eql(1);pm.expect(r.recovery.quarantinedAt).eql(null);});",'r-{{runKey}}');
req('Same key redrive replay','POST','/api/demo/recovery/payments/{{paymentId}}/redrive',{},status(200)+"pm.test('no additional dispatch',()=>{const r=pm.response.json();pm.expect(r.replayed).eql(true);pm.expect(r.recovery.attempts).eql(4);});",'r-{{runKey}}');
req('Different key terminal redrive rejected','POST','/api/demo/recovery/payments/{{paymentId}}/redrive',{},status(409),'other-{{runKey}}');
req('Original booking identity preserved','GET','/api/bookings/{{bad}}',undefined,status(200)+"pm.test('confirmation uses original payment',()=>{const b=pm.response.json();pm.expect(b.state).eql('CONFIRMED');pm.expect(b.payment.id).eql(pm.environment.get('paymentId'));});");
req('Confirmation happens once','GET','/api/bookings/{{bad}}/audit',undefined,status(200)+"pm.test('one confirmation audit',()=>pm.expect(pm.response.json().filter(x=>x.action==='CONFIRMED').length).eql(1));");
req('Malformed fixture rejected','POST','/api/demo/recovery/payments/{{paymentId}}/fixture',{},status(400));
fs.writeFileSync('postman/poison.postman_collection.json',JSON.stringify({info:{name:'Ticket booking scenario 3: poison isolation',description:'Enable compose.poison.yml; maintenance must be OFF on both replicas. Default local provider. Sequential retained fixtures; three retries use1100ms waits. No deletion/reset. Runtime harness adds restart, cross-replica race, late refund, redrive exhaustion and DB pause.',schema:'https://schema.getpostman.com/json/collection/v2.1.0/collection.json'},item:items},null,2));
fs.writeFileSync('postman/poison.postman_environment.json',JSON.stringify({name:'Ticket poison local',values:[{key:'baseUrl',value:'http://localhost:8130',enabled:true}],_postman_variable_scope:'environment'},null,2));

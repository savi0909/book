import fs from 'node:fs';
const event = (listen, code) => ({ listen, script: { type: 'text/javascript', exec: code.split('\n') } });
const status = n => `pm.test('HTTP ${n}',()=>pm.response.to.have.status(${n}));`;
const test = (name, expression) => `pm.test(${JSON.stringify(name)},()=>pm.expect(${expression}).true);`;
const save = name => `pm.environment.set('${name}',pm.response.json().id);`;
const bodyState = state => test('booking state', `pm.response.json().state==='${state}'`);
const key = value => [{ key: 'Idempotency-Key', value }];
function req(name, method, path, body, checks, headers = [], peer = false, before = '') {
  return { name, event: [...(before ? [event('prerequest', before)] : []), event('test', checks)], request: {
    method, url: (peer ? '{{peerUrl}}' : '{{baseUrl}}') + path,
    header: [...(body ? [{ key: 'Content-Type', value: 'application/json' }] : []), ...headers],
    ...(body ? { body: { mode: 'raw', raw: JSON.stringify(body, null, 2), options: { raw: { language: 'json' } } } } : {})
  } };
}
function poll(name, variable, predicate) {
  return req(name, 'GET', `/api/bookings/{{${variable}}}`, null, `${status(200)}
const b=pm.response.json();
if(!(${predicate})) {
  const n=Number(pm.environment.get('pollCount')||0)+1;pm.environment.set('pollCount',n);
  if(n<60) pm.execution.setNextRequest(pm.info.requestName);
  else {pm.environment.unset('pollCount');pm.test('bounded poll converged',()=>pm.expect(false).true);}
} else {pm.environment.unset('pollCount');pm.test('state converged',()=>pm.expect(true).true);}`);
}
const hold = (seat, ttl = 120, buyer = 'postman-{{runId}}') => ({ eventId: '{{eventId}}', seatNumber: seat, buyerId: buyer, ttlSeconds: ttl });
const checkout = (scenario, delayMs = 0) => ({ scenario, delayMs });
const folders = [
  { name: 'Health and fresh retained fixture', item: [
    req('Replica A liveness', 'GET', '/health', null, status(200) + test('replica A', "pm.response.json().instance==='api-a'")),
    req('Replica B liveness', 'GET', '/health', null, status(200) + test('replica B', "pm.response.json().instance==='api-b'"), [], true),
    req('Database health', 'GET', '/actuator/health', null, status(200)),
    req('Readiness probe', 'GET', '/actuator/health/readiness', null, status(200)),
    req('Liveness probe', 'GET', '/actuator/health/liveness', null, status(200)),
    req('Actuator info', 'GET', '/actuator/info', null, status(200)),
    req('Create a fresh demo event (mutates)', 'POST', '/api/demo/events', { name: 'postman-{{runId}}', seatCount: 12 }, status(201) + save('eventId'), [], false,
      "pm.environment.set('runId',pm.variables.replaceIn('{{$guid}}'));for(const k of ['eventId','bookingId','paymentId','lateId','latePayment','unknownId','unknownPayment','failedId','failedPayment','pollCount'])pm.environment.unset(k);"),
    req('List events', 'GET', '/api/events?limit=5&offset=0', null, status(200) + test('bounded events', 'Array.isArray(pm.response.json())&&pm.response.json().length<=5')),
    req('Read selected event', 'GET', '/api/events/{{eventId}}', null, status(200) + test('twelve seats', 'pm.response.json().seatCount===12')),
    req('Read peer seat availability', 'GET', '/api/events/{{eventId}}/seats?limit=4&offset=0', null, status(200) + test('available seats', "pm.response.json().every(s=>s.availability==='AVAILABLE')"), [], true)
  ] },
  { name: 'Durable holds and confirmation', item: [
    req('Hold seat one', 'POST', '/api/holds', hold(1), status(201) + save('bookingId') + bodyState('HELD'), key('hold-{{runId}}')),
    req('Replay hold through peer', 'POST', '/api/holds', hold(1), status(200) + test('same hold', "pm.response.json().id===pm.environment.get('bookingId')&&pm.response.json().replayed"), key('hold-{{runId}}'), true),
    req('Reject changed hold payload', 'POST', '/api/holds', hold(2), status(409), key('hold-{{runId}}')),
    req('Reject competing buyer', 'POST', '/api/holds', hold(1, 120, 'contender-{{runId}}'), status(409) + test('seat conflict', "pm.response.json().code==='SEAT_UNAVAILABLE'"), key('contender-{{runId}}'), true),
    req('Read booking', 'GET', '/api/bookings/{{bookingId}}', null, status(200) + bodyState('HELD')),
    req('Read booking audit', 'GET', '/api/bookings/{{bookingId}}/audit', null, status(200) + test('hold audit', "pm.response.json().some(a=>a.action==='HELD')")),
    req('Begin successful local checkout', 'POST', '/api/bookings/{{bookingId}}/checkout', checkout('SUCCESS'), status(202) + "pm.environment.set('paymentId',pm.response.json().payment.id);", key('checkout-{{runId}}')),
    req('Replay checkout through peer', 'POST', '/api/bookings/{{bookingId}}/checkout', checkout('SUCCESS'), status(200) + test('same payment', "pm.response.json().payment.id===pm.environment.get('paymentId')"), key('checkout-{{runId}}'), true),
    req('Reject changed checkout input', 'POST', '/api/bookings/{{bookingId}}/checkout', checkout('FAILURE'), status(409), key('checkout-{{runId}}')),
    poll('Wait for confirmation', 'bookingId', "b.state==='CONFIRMED'"),
    req('Read payment', 'GET', '/api/payments/{{paymentId}}', null, status(200) + test('paid', "pm.response.json().state==='SUCCESS'")),
    req('Deliver a repeated success event', 'POST', '/api/demo/payments/{{paymentId}}/callback', { eventId: 'event-{{runId}}', outcome: 'SUCCESS' }, status(200) + bodyState('CONFIRMED')),
    req('Replay same provider event', 'POST', '/api/demo/payments/{{paymentId}}/callback', { eventId: 'event-{{runId}}', outcome: 'SUCCESS' }, status(200) + test('callback replay', 'pm.response.json().replayed'), [], true),
    req('Reject conflicting provider outcome', 'POST', '/api/demo/payments/{{paymentId}}/callback', { eventId: 'bad-{{runId}}', outcome: 'FAILURE' }, status(409)),
    req('Reconcile terminal payment', 'POST', '/api/demo/payments/{{paymentId}}/reconcile', null, status(200) + bodyState('CONFIRMED')),
    req('Reject unnecessary refund', 'POST', '/api/demo/payments/{{paymentId}}/refund', null, status(409)),
    req('Cancel confirmed booking', 'POST', '/api/bookings/{{bookingId}}/cancel', null, status(200) + bodyState('CANCELLED') + test('refund required', "pm.response.json().reconciliation==='REFUND_REQUIRED'")),
    req('Record simulated refund', 'POST', '/api/demo/payments/{{paymentId}}/refund', null, status(200) + test('refund recorded', "pm.response.json().reconciliation==='REFUNDED_SIMULATED'")),
    req('Replay refund', 'POST', '/api/demo/payments/{{paymentId}}/refund', null, status(200) + test('refund replay', 'pm.response.json().replayed')),
    req('Replay cancellation', 'POST', '/api/bookings/{{bookingId}}/cancel', null, status(200) + bodyState('CANCELLED'))
  ] },
  { name: 'Expiry and late success', item: [
    req('Hold seat two for two seconds', 'POST', '/api/holds', hold(2, 2), status(201) + save('lateId'), key('late-{{runId}}')),
    req('Checkout with four-second provider delay', 'POST', '/api/bookings/{{lateId}}/checkout', checkout('SUCCESS', 4000), status(202) + "pm.environment.set('latePayment',pm.response.json().payment.id);", key('late-checkout-{{runId}}')),
    poll('Wait for expiry', 'lateId', "b.state==='EXPIRED'"),
    req('Reserve expired seat for another buyer', 'POST', '/api/holds', hold(2, 120, 'replacement-{{runId}}'), status(201) + save('replacementId'), key('replacement-{{runId}}'), true),
    poll('Wait for late payment success', 'lateId', "b.payment?.state==='SUCCESS'"),
    req('Observe late-success reconciliation', 'GET', '/api/bookings/{{lateId}}', null, status(200) + bodyState('EXPIRED') + test('refund instead of confirmation', "pm.response.json().reconciliation==='REFUND_REQUIRED'")),
    req('Replacement still owns the seat', 'GET', '/api/bookings/{{replacementId}}', null, status(200) + bodyState('HELD'), [], true),
    req('Refund expired booking locally', 'POST', '/api/demo/payments/{{latePayment}}/refund', null, status(200) + test('simulated refund', "pm.response.json().reconciliation==='REFUNDED_SIMULATED'")),
    req('Replay expired original hold', 'POST', '/api/holds', hold(2, 2), status(200) + bodyState('EXPIRED'), key('late-{{runId}}'))
  ] },
  { name: 'Unknown outcome and failure', item: [
    req('Hold seat three', 'POST', '/api/holds', hold(3), status(201) + save('unknownId'), key('unknown-{{runId}}')),
    req('Simulate unknown response with delay', 'POST', '/api/bookings/{{unknownId}}/checkout', checkout('UNKNOWN', 2000), status(202) + "pm.environment.set('unknownPayment',pm.response.json().payment.id);", key('unknown-checkout-{{runId}}')),
    req('Cancel while outcome unknown', 'POST', '/api/bookings/{{unknownId}}/cancel', null, status(200) + bodyState('CANCELLED')),
    poll('Wait for recovered unknown outcome', 'unknownId', "b.payment?.state==='SUCCESS'"),
    req('Unknown cancellation needs refund', 'GET', '/api/bookings/{{unknownId}}', null, status(200) + bodyState('CANCELLED') + test('refund after recovery', "pm.response.json().reconciliation==='REFUND_REQUIRED'")),
    req('Refund recovered local payment', 'POST', '/api/demo/payments/{{unknownPayment}}/refund', null, status(200)),
    req('Hold seat four', 'POST', '/api/holds', hold(4), status(201) + save('failedId'), key('failure-{{runId}}')),
    req('Simulate payment failure', 'POST', '/api/bookings/{{failedId}}/checkout', checkout('FAILURE'), status(202) + "pm.environment.set('failedPayment',pm.response.json().payment.id);", key('failure-checkout-{{runId}}')),
    poll('Wait for failure', 'failedId', "b.state==='PAYMENT_FAILED'"),
    req('Reject event ID reused for another payment', 'POST', '/api/demo/payments/{{failedPayment}}/callback', { eventId: 'event-{{runId}}', outcome: 'FAILURE' }, status(409) + test('event conflict', "pm.response.json().code==='EVENT_CONFLICT'"))
  ] },
  { name: 'Validation and statistics', item: [
    req('Missing booking', 'GET', '/api/bookings/00000000-0000-0000-0000-000000000000', null, status(404)),
    req('Missing hold key', 'POST', '/api/holds', hold(5), status(400)),
    req('Invalid hold key', 'POST', '/api/holds', hold(5), status(400), key('bad key')),
    req('Invalid fractional seat count', 'POST', '/api/demo/events', { name: 'invalid', seatCount: 2.5 }, status(400)),
    req('Invalid event seat bound', 'POST', '/api/demo/events', { name: 'invalid', seatCount: 201 }, status(400)),
    req('Invalid page limit', 'GET', '/api/events?limit=0', null, status(400)),
    req('Invalid UUID', 'GET', '/api/events/not-a-uuid', null, status(400)),
    req('Observe statistics', 'GET', '/api/stats', null, status(200) + test('stats arrays', 'Array.isArray(pm.response.json().bookings)&&Array.isArray(pm.response.json().payments)'))
  ] }
];
fs.mkdirSync('postman', { recursive: true });
fs.writeFileSync('postman/ticket-booking-lab.postman_collection.json', JSON.stringify({
  info: { name: 'Ticket booking interview lab', schema: 'https://schema.getpostman.com/json/collection/v2.1.0/collection.json', description: 'Run in order. Local simulation only, no charges. New event per run; all state retained. Polls wait 250ms and stop after 60 attempts.' },
  event: [event('prerequest', 'setTimeout(()=>{},250); // Bound polling pace in Postman/Newman.')], item: folders
}, null, 2) + '\n');
fs.writeFileSync('postman/local.postman_environment.json', JSON.stringify({ name: 'Ticket booking local', values: [
  { key: 'baseUrl', value: 'http://localhost:8105', enabled: true },
  { key: 'peerUrl', value: 'http://localhost:8106', enabled: true }
], _postman_variable_scope: 'environment' }, null, 2) + '\n');
console.log('Generated', folders.reduce((n, f) => n + f.item.length, 0), 'named requests.');

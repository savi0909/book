import fs from 'node:fs';

const script = (listen, code) => ({ listen, script: { type: 'text/javascript', exec: code.split('\n') } });
const status = code => `pm.test('HTTP ${code}',()=>pm.response.to.have.status(${code}));`;
const test = (name, expression) => `pm.test(${JSON.stringify(name)},()=>pm.expect(${expression}).true);`;
function req(name, base, method, path, body, checks, headers = [], before = '') {
  return { name, request: { method, url: `{{${base}}}` + path,
    header: [{ key: 'Content-Type', value: 'application/json' }, ...headers],
    ...(body ? { body: { mode: 'raw', raw: JSON.stringify(body, null, 2), options: { raw: { language: 'json' } } } } : {}) },
    event: [...(before ? [script('prerequest', before)] : []), script('test', checks)] };
}
const key = value => ({ key: 'Idempotency-Key', value });
const control = (name, base, action, checks) => req(name, base, action === 'status' ? 'GET' : 'POST',
  '/api/demo/failover/' + action, null, status(200) + checks);
const item = [
  control('Resume A before study', 'apiA', 'resume', test('accepting', '!pm.response.json().admission.draining')),
  control('Resume B before study', 'apiB', 'resume', test('accepting', '!pm.response.json().admission.draining')),
  req('Shared endpoint identifies its replica', 'baseUrl', 'GET', '/health', null,
    status(200) + test('replica header', "['api-a','api-b'].includes(pm.response.headers.get('X-Booking-Instance'))")),
  req('Proxy excludes demo controls', 'baseUrl', 'POST', '/api/demo/failover/drain', null, status(404)),
  req('Create retained failover fixture', 'baseUrl', 'POST', '/api/demo/events',
    { name: 'failover-postman-{{runId}}', seatCount: 2 }, status(201) + "pm.environment.set('eventId',pm.response.json().id);", [],
    "pm.environment.set('runId',pm.variables.replaceIn('{{$guid}}'));for(const k of ['eventId','bookingId','paymentId'])pm.environment.unset(k);"),
  req('Hold through shared endpoint', 'baseUrl', 'POST', '/api/holds',
    { eventId: '{{eventId}}', seatNumber: 1, buyerId: 'failover-{{runId}}', ttlSeconds: 120 },
    status(201) + "pm.environment.set('bookingId',pm.response.json().id);", [key('failover-hold-{{runId}}')]),
  req('Checkout with bounded post-commit response delay', 'baseUrl', 'POST', '/api/bookings/{{bookingId}}/checkout',
    { scenario: 'SUCCESS', delayMs: 0 }, status(202) + "pm.environment.set('paymentId',pm.response.json().payment.id);",
    [key('failover-checkout-{{runId}}'), { key: 'X-Lab-Response-Delay-Ms', value: '1000' }]),
  req('Replay original checkout intent', 'baseUrl', 'POST', '/api/bookings/{{bookingId}}/checkout',
    { scenario: 'SUCCESS', delayMs: 0 }, status(200) + test('same intent',
      "pm.response.json().replayed&&pm.response.json().payment.id===pm.environment.get('paymentId')"), [key('failover-checkout-{{runId}}')]),
  control('Drain replica A', 'apiA', 'drain', test('draining', 'pm.response.json().admission.draining')),
  control('Observe A admission', 'apiA', 'status', test('draining', 'pm.response.json().admission.draining')),
  req('A readiness withdrawn', 'apiA', 'GET', '/actuator/health/readiness', null, status(503)),
  req('A remains live', 'apiA', 'GET', '/actuator/health/liveness', null, status(200)),
  req('A rejects new business admission', 'apiA', 'GET', '/api/events', null,
    status(503) + test('drain error', "pm.response.json().code==='INSTANCE_DRAINING'")),
  req('B reads the durable booking', 'apiB', 'GET', '/api/bookings/{{bookingId}}', null,
    status(200) + test('same intent', "pm.response.json().payment.id===pm.environment.get('paymentId')")),
  control('Resume replica A after study', 'apiA', 'resume', test('accepting', '!pm.response.json().admission.draining')),
  req('A readiness restored', 'apiA', 'GET', '/actuator/health/readiness', null, status(200)),
  req('Reject out-of-range response delay', 'apiB', 'POST', '/api/bookings/{{bookingId}}/checkout',
    { scenario: 'SUCCESS', delayMs: 0 }, status(400), [key('failover-checkout-{{runId}}'),
      { key: 'X-Lab-Response-Delay-Ms', value: '10001' }])
];
fs.writeFileSync('postman/failover.postman_collection.json', JSON.stringify({ info: {
  name: 'Ticket booking - API failover and drain',
  description: 'Optional failover overlay required. Retains fixtures and toggles A drain. Run separately from learn-failover.mjs. Docker crash/shutdown assertions live in that script.',
  schema: 'https://schema.getpostman.com/json/collection/v2.1.0/collection.json' }, item }, null, 2) + '\n');
fs.writeFileSync('postman/failover.postman_environment.json', JSON.stringify({ name: 'Ticket booking failover local',
  values: [{ key: 'baseUrl', value: 'http://localhost:8107', enabled: true },
    { key: 'apiA', value: 'http://localhost:8105', enabled: true },
    { key: 'apiB', value: 'http://localhost:8106', enabled: true }], _postman_variable_scope: 'environment' }, null, 2) + '\n');

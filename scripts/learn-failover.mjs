import assert from 'node:assert/strict';
import fs from 'node:fs';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { randomUUID } from 'node:crypto';

if (process.argv.length > 2) {
  console.log('Run from sd-book-my-show: node scripts/learn-failover.mjs (no arguments).');
  process.exit(process.argv.length === 3 && process.argv[2] === '--help' ? 0 : 1);
}
if (!fs.existsSync('compose.yml') || !fs.existsSync('compose.failover.yml')) {
  console.error('Run from the sd-book-my-show directory; no experiment was started.');
  process.exit(1);
}

const exec = promisify(execFile);
const A = 'http://localhost:8130', B = 'http://localhost:8131', G = 'http://localhost:8132';
const run = randomUUID();
const evidence = { run, startedAt: new Date().toISOString(), checks: [], scenarios: [], restored: false, passed: false };
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
function check(condition, label) { assert.ok(condition, label); evidence.checks.push(label); }
async function compose(...args) {
  return exec('docker', ['compose', '-f', 'compose.yml', '-f', 'compose.failover.yml', ...args], { timeout: 120000 });
}
async function call(base, method, path, body, key, delay) {
  const r = await fetch(base + path, { method,
    headers: { 'Content-Type': 'application/json', ...(key ? { 'Idempotency-Key': key } : {}),
      ...(delay ? { 'X-Lab-Response-Delay-Ms': String(delay) } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(18000) });
  const text = await r.text();
  let data; try { data = JSON.parse(text); } catch { data = text; }
  return { status: r.status, instance: r.headers.get('x-booking-instance'), data };
}
async function until(label, work, predicate, timeout = 30000) {
  const start = Date.now(); let last;
  while (Date.now() - start < timeout) {
    try { last = await work(); if (predicate(last)) return last; } catch (error) { last = error.message; }
    await sleep(150);
  }
  throw new Error(`${label}: ${JSON.stringify(last)}`);
}
const control = (base, action) => call(base, action === 'status' ? 'GET' : 'POST', '/api/demo/failover/' + action);
const ready = base => until('ready ' + base, () => call(base, 'GET', '/actuator/health/readiness'), r => r.status === 200);
async function only(instance) {
  let consecutive = 0;
  await until('route only ' + instance, () => call(G, 'GET', '/health'), r => {
    consecutive = r.status === 200 && r.data.instance === instance ? consecutive + 1 : 0;
    return consecutive >= 5;
  });
}
async function fixture(name) {
  const event = await call(G, 'POST', '/api/demo/events', { name: `${name}-${run}`, seatCount: 3 });
  check(event.status === 201, name + ': event created');
  const body = { eventId: event.data.id, seatNumber: 1, buyerId: 'failover-' + run, ttlSeconds: 120 };
  const hold = await call(G, 'POST', '/api/holds', body, name + '-hold-' + run);
  check(hold.status === 201, name + ': hold created');
  return { event: event.data, booking: hold.data };
}
async function waiting(booking) {
  return until('checkout committed and response withheld', () => control(A, 'status'),
    r => r.status === 200 && r.data.waitingBookings.includes(booking.id));
}
async function verifyIntent(booking, payment, key, label) {
  const replay = await call(G, 'POST', `/api/bookings/${booking.id}/checkout`, { scenario: 'SUCCESS', delayMs: 0 }, key);
  check(replay.status === 200 && replay.data.replayed, label + ': replay acknowledged');
  check(replay.data.payment.id === payment, label + ': same payment ID');
  const audit = await call(G, 'GET', `/api/bookings/${booking.id}/audit`);
  check(audit.status === 200 && audit.data.filter(x => x.action === 'CHECKOUT_STARTED').length === 1,
    label + ': one checkout audit');
  const sql = await compose('exec', '-T', 'postgres', 'psql', '-U', 'booking_demo', '-d', 'booking', '-At', '-c',
    `SELECT count(*) FROM payments WHERE booking_id='${booking.id}';`);
  check(sql.stdout.trim() === '1', label + ': exactly one durable payment row');
  await until(label + ': confirmation recovers', () => call(G, 'GET', `/api/bookings/${booking.id}`),
    r => r.status === 200 && r.data.state === 'CONFIRMED');
  check(true, label + ': confirmation converged');
}
try {
  await ready(A); await ready(B);
  check((await control(A, 'resume')).status === 200, 'A controls enabled');
  check((await control(B, 'resume')).status === 200, 'B controls enabled');
  const seen = new Set();
  await until('both replicas routed', async () => {
    const r = await call(G, 'GET', '/health'); if (r.status === 200) seen.add(r.instance); return r;
  }, () => seen.has('api-a') && seen.has('api-b'));
  check(true, 'shared endpoint reaches both replicas');
  check((await control(G, 'drain')).status === 404, 'shared endpoint excludes replica controls');

  await control(A, 'drain'); await control(B, 'drain');
  await until('no ready backend', () => call(G, 'GET', '/api/events'), r => r.status === 503);
  check(true, 'both drained produces bounded 503');
  check((await call(A, 'GET', '/actuator/health/liveness')).status === 200, 'drain preserves liveness');
  await control(A, 'resume'); await only('api-a');

  const abrupt = await fixture('abrupt');
  const abruptKey = 'abrupt-checkout-' + run;
  const pendingCrash = call(G, 'POST', `/api/bookings/${abrupt.booking.id}/checkout`,
    { scenario: 'SUCCESS', delayMs: 0 }, abruptKey, 10000).catch(error => ({ status: 0, error: error.message }));
  await waiting(abrupt.booking);
  const committed = await call(A, 'GET', `/api/bookings/${abrupt.booking.id}`);
  check(committed.status === 200 && !!committed.data.payment, 'crash: intent visible before response');
  await control(B, 'resume'); await ready(B);
  await until('B reachable through gateway', () => call(G, 'GET', '/health'), r => r.status === 200 && r.instance === 'api-b');
  const crashedAt = Date.now();
  await compose('kill', '-s', 'SIGKILL', 'api-a');
  const lost = await pendingCrash;
  check(lost.status === 0 || lost.status >= 500, 'crash: proxy does not hide loss by replaying checkout');
  await only('api-b');
  const routingRecoveryMs = Date.now() - crashedAt;
  await verifyIntent(abrupt.booking, committed.data.payment.id, abruptKey, 'crash');
  const duringCrash = await fixture('surviving-b');
  check(!!duringCrash.booking.id, 'B admits fresh bookings after A failure');
  evidence.scenarios.push({ name: 'abrupt', bookingId: abrupt.booking.id, originalResponseStatus: lost.status, routingRecoveryMs });

  await compose('start', 'api-a'); await ready(A);
  await control(B, 'drain'); await only('api-a');
  const graceful = await fixture('graceful');
  const gracefulKey = 'graceful-checkout-' + run;
  const pendingGraceful = call(G, 'POST', `/api/bookings/${graceful.booking.id}/checkout`,
    { scenario: 'SUCCESS', delayMs: 0 }, gracefulKey, 8000).catch(error => ({ status: 0, error: error.message }));
  await waiting(graceful.booking);
  const beforeDrain = await call(A, 'GET', `/api/bookings/${graceful.booking.id}`);
  const drain = await control(A, 'drain');
  check(drain.status === 200 && drain.data.admission.inFlight >= 1, 'drain observes admitted in-flight work');
  check((await call(A, 'GET', '/api/events')).status === 503, 'drain rejects new direct business traffic');
  check((await call(A, 'GET', '/actuator/health/readiness')).status === 503, 'drain withdraws readiness');
  check((await call(A, 'GET', '/actuator/health/liveness')).status === 200, 'drained process remains live');
  await control(B, 'resume'); await only('api-b');
  const stoppingAt = Date.now();
  const stopped = compose('stop', '-t', '20', 'api-a');
  const duringDrain = await fixture('during-drain');
  check(!!duringDrain.booking.id, 'B accepts new bookings during A shutdown');
  const delivered = await pendingGraceful;
  check(delivered.status === 202 && delivered.data.payment.id === beforeDrain.data.payment.id,
    'graceful stop allows admitted response to finish');
  await stopped;
  const shutdownMs = Date.now() - stoppingAt;
  const id = (await compose('ps', '-aq', 'api-a')).stdout.trim();
  const state = JSON.parse((await exec('docker', ['inspect', '--format', '{{json .State}}', id])).stdout);
  check([0, 143].includes(state.ExitCode) && !state.OOMKilled, 'graceful stop exits normally or via SIGTERM, without OOM');
  await verifyIntent(graceful.booking, beforeDrain.data.payment.id, gracefulKey, 'graceful');
  evidence.scenarios.push({ name: 'graceful', bookingId: graceful.booking.id,
    shutdownMs, exitCode: state.ExitCode });
  evidence.passed = true;
} catch (error) {
  evidence.error = error.stack;
  process.exitCode = 1;
} finally {
  try {
    await compose('start', 'api-a', 'api-b', 'gateway');
    await until('A control after restart', () => control(A, 'resume'), r => r.status === 200, 60000);
    await until('B control after restart', () => control(B, 'resume'), r => r.status === 200, 60000);
    await ready(A); await ready(B);
    const finalSeen = new Set();
    await until('restored routing', async () => { const r = await call(G, 'GET', '/health');
      if (r.status === 200) finalSeen.add(r.instance); return r; }, () => finalSeen.size === 2);
    evidence.restored = true;
  } catch (error) { evidence.restoreError = error.stack; evidence.passed = false; process.exitCode = 1; }
  evidence.finishedAt = new Date().toISOString();
  fs.mkdirSync('target', { recursive: true });
  fs.writeFileSync('target/failover-runtime-evidence.json', JSON.stringify(evidence, null, 2));
  console.log(JSON.stringify(evidence, null, 2));
}

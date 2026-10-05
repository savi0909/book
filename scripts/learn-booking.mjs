import assert from 'node:assert/strict';
import fs from 'node:fs';
import { execFileSync } from 'node:child_process';
import { randomUUID } from 'node:crypto';

const A = process.env.API_A ?? 'http://localhost:8130';
const B = process.env.API_B ?? 'http://localhost:8131';
const run = randomUUID();
let checks = 0;
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
function check(condition, message) { assert.ok(condition, message); checks++; }
function compose(...args) { execFileSync('docker', ['compose', ...args], { stdio: 'pipe' }); }
async function call(base, method, path, body, key) {
  const response = await fetch(base + path, {
    method, headers: { 'Content-Type': 'application/json', ...(key ? { 'Idempotency-Key': key } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(15000)
  });
  return { status: response.status, data: await response.json() };
}
async function poll(base, path, predicate, timeout = 15000) {
  const end = Date.now() + timeout;
  while (Date.now() < end) {
    const result = await call(base, 'GET', path);
    check(result.status === 200, 'poll HTTP status');
    if (predicate(result.data)) return result.data;
    await sleep(150);
  }
  throw new Error('Timed out: ' + path);
}
let event;
let evidence;
async function hold(seat, buyer = randomUUID(), key = randomUUID(), ttlSeconds = 120, base = A) {
  return call(base, 'POST', '/api/holds', { eventId: event.id, seatNumber: seat, buyerId: buyer, ttlSeconds }, key);
}
async function checkout(booking, scenario, delayMs = 0, key = randomUUID(), base = A) {
  return call(base, 'POST', `/api/bookings/${booking.id}/checkout`, { scenario, delayMs }, key);
}

try {
  check((await call(A, 'GET', '/health')).data.instance === 'api-a', 'real replica A');
  check((await call(B, 'GET', '/health')).data.instance === 'api-b', 'real replica B');
  const created = await call(A, 'POST', '/api/demo/events', { name: `runtime-${run}`, seatCount: 16 });
  check(created.status === 201, 'event created'); event = created.data;
  const contenders = await Promise.all(Array.from({ length: 24 }, (_, i) => hold(1, `race-${run}-${i}`, `race-${i}`, 120, i % 2 ? A : B)));
  check(contenders.filter(x => x.status === 201).length === 1, 'one winner across replicas');
  check(contenders.filter(x => x.status === 409).length === 23, 'all other buyers conflict');
  const winner = contenders.find(x => x.status === 201).data;
  const duplicateBuyer = `same-${run}`, duplicateKey = `same-${run}`;
  const duplicates = await Promise.all(Array.from({ length: 20 }, (_, i) => hold(2, duplicateBuyer, duplicateKey, 120, i % 2 ? A : B)));
  check(duplicates.filter(x => x.status === 201).length === 1, 'one fresh duplicate hold');
  check(duplicates.filter(x => x.status === 200).length === 19, 'all duplicate holds replay');
  const duplicate = duplicates[0].data;
  check(new Set(duplicates.map(x => x.data.id)).size === 1, 'one duplicate booking ID');
  check((await hold(8, duplicateBuyer, duplicateKey)).status === 409, 'changed hold payload rejected');
  const checkoutKey = `checkout-${run}`;
  const starts = await Promise.all(Array.from({ length: 12 }, (_, i) => checkout(winner, 'SUCCESS', 0, checkoutKey, i % 2 ? A : B)));
  check(starts.filter(x => x.status === 202).length === 1, 'one fresh checkout');
  check(starts.filter(x => x.status === 200).length === 11, 'duplicate checkout replay');
  check(new Set(starts.map(x => x.data.payment.id)).size === 1, 'one payment intent');
  await poll(B, `/api/bookings/${winner.id}`, x => x.state === 'CONFIRMED');
  check((await hold(1)).status === 409, 'confirmed seat unavailable');
  check((await checkout(winner, 'FAILURE', 0, checkoutKey)).status === 409, 'changed checkout input rejected');

  const unknown = (await hold(3)).data;
  const uncertain = (await checkout(unknown, 'UNKNOWN')).data;
  await poll(B, `/api/bookings/${unknown.id}`, x => x.payment?.state === 'UNKNOWN');
  const callback = { eventId: `callback-${run}`, outcome: 'SUCCESS' };
  const callbacks = await Promise.all(Array.from({ length: 12 }, (_, i) => call(i % 2 ? A : B, 'POST', `/api/demo/payments/${uncertain.payment.id}/callback`, callback)));
  for (const result of callbacks) check(result.status === 200 && result.data.state === 'CONFIRMED', 'duplicate callback confirmed');
  const audit = (await call(A, 'GET', `/api/bookings/${unknown.id}/audit`)).data;
  check(audit.filter(x => x.action === 'CONFIRMED').length === 1, 'one confirmation audit');
  check((await call(B, 'POST', `/api/demo/payments/${uncertain.payment.id}/callback`, { eventId: `bad-${run}`, outcome: 'FAILURE' })).status === 409, 'outcome conflict rejected');

  const expiring = (await hold(4, randomUUID(), randomUUID(), 2)).data;
  const delayed = (await checkout(expiring, 'SUCCESS', 4000)).data;
  await poll(B, `/api/bookings/${expiring.id}`, x => x.state === 'EXPIRED');
  const replacement = (await hold(4)).data;
  const late = await poll(B, `/api/bookings/${expiring.id}`, x => x.payment?.state === 'SUCCESS');
  check(late.state === 'EXPIRED' && late.reconciliation === 'REFUND_REQUIRED', 'late success requires refund');
  check((await call(A, 'GET', `/api/bookings/${replacement.id}`)).data.state === 'HELD', 'late callback cannot steal new hold');
  check((await call(B, 'POST', `/api/demo/payments/${delayed.payment.id}/refund`)).data.reconciliation === 'REFUNDED_SIMULATED', 'simulated refund');
  check((await call(A, 'POST', `/api/demo/payments/${delayed.payment.id}/refund`)).data.replayed, 'duplicate refund replay');

  const cancelled = (await hold(5)).data;
  const pendingCancel = (await checkout(cancelled, 'UNKNOWN', 2000)).data;
  check((await call(B, 'POST', `/api/bookings/${cancelled.id}/cancel`)).data.state === 'CANCELLED', 'cancel unknown checkout');
  const afterCancel = await hold(5); check(afterCancel.status === 201, 'cancelled seat reused');
  const cancelledPaid = await poll(A, `/api/bookings/${cancelled.id}`, x => x.payment?.state === 'SUCCESS');
  check(cancelledPaid.state === 'CANCELLED' && cancelledPaid.reconciliation === 'REFUND_REQUIRED', 'success after cancellation reconciles');
  await call(A, 'POST', `/api/demo/payments/${pendingCancel.payment.id}/refund`);

  const failed = (await hold(6)).data;
  await checkout(failed, 'FAILURE');
  await poll(B, `/api/bookings/${failed.id}`, x => x.state === 'PAYMENT_FAILED');
  check((await hold(6)).status === 201, 'failure releases seat');

  const restarting = (await hold(7)).data;
  const restartIntent = (await checkout(restarting, 'UNKNOWN', 1000)).data;
  await poll(B, `/api/bookings/${restarting.id}`, x => x.payment?.state === 'UNKNOWN');
  compose('stop', 'api-a', 'api-b');
  compose('restart', 'postgres');
  await sleep(2000);
  compose('up', '-d', '--wait', 'api-a', 'api-b');
  const recovered = await poll(B, `/api/bookings/${restarting.id}`, x => x.state === 'CONFIRMED');
  check(recovered.payment.id === restartIntent.payment.id, 'restart retains intent and recovers outcome');
  const replay = await hold(2, duplicateBuyer, duplicateKey, 120, B);
  check(replay.status === 200 && replay.data.id === duplicate.id, 'hold key survives PostgreSQL and API restart');

  compose('pause', 'postgres');
  try {
    check((await call(A, 'GET', '/api/events')).status === 503, 'database outage returns bounded 503');
    check((await call(B, 'GET', '/health')).status === 200, 'process liveness remains up');
  } finally { compose('unpause', 'postgres'); }
  await poll(A, '/api/events', x => Array.isArray(x));
  const seats = (await call(B, 'GET', `/api/events/${event.id}/seats`)).data;
  check(seats[0].availability === 'BOOKED', 'confirmed seat persists');
  check(seats[1].availability === 'HELD', 'shared held seat persists');
  check((await call(A, 'GET', '/api/events?limit=0')).status === 400, 'pagination validated');
  evidence = { run, checks, eventId: event.id, scenarios: ['cross-replica contention', 'durable hold and checkout replay', 'duplicate callbacks', 'expiry/reassignment/late success', 'cancellation', 'failure', 'unknown recovery', 'API/PostgreSQL restart', 'database outage'], passed: true };
} finally {
  // Restore only this stack after an experiment failure; keep all files and volumes.
  try { compose('unpause', 'postgres'); } catch {}
  // Docker's cached health can still say unhealthy immediately after unpause.
  // Wait for its next successful probe before Compose evaluates dependencies.
  const postgresId = execFileSync('docker', ['compose', 'ps', '-q', 'postgres'], { encoding: 'utf8' }).trim();
  let healthy = false;
  for (let i = 0; i < 80; i++) {
    if (execFileSync('docker', ['inspect', '--format', '{{.State.Health.Status}}', postgresId], { encoding: 'utf8' }).trim() === 'healthy') { healthy = true; break; }
    await sleep(250);
  }
  assert.ok(healthy, 'PostgreSQL Docker health restored');
  compose('up', '-d', '--wait');
}
fs.writeFileSync('target/runtime-evidence.json', JSON.stringify(evidence, null, 2));
console.log(JSON.stringify(evidence, null, 2));

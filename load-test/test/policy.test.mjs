import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { configCheck, delayFor, execute, manifestCheck, operations, random, retryAfter, summarize } from '../lib.mjs';
import { runArm } from '../runner.mjs';
import { generator } from '../cli.mjs';

const c = configCheck(JSON.parse(fs.readFileSync(new URL('../config.small.json', import.meta.url))));
const manifest = { version: 1, runId: '0123456789abcdef', config: c,
  events: { immediate: '00000000-0000-4000-8000-000000000001', jitter: '00000000-0000-4000-8000-000000000002' } };
const op = operations(c, manifest, 'immediate')[0];
function booking(op, replayed = false) {
  return { ...op.payload, id: '00000000-0000-4000-8000-000000000003', state: 'HELD', replayed,
    createdAt: '2026-10-06T00:00:00Z', expiresAt: '2026-10-06T00:02:00Z' };
}
function clock(send) {
  let time = 0;
  const waits = [];
  return { now: () => time, sleep: async ms => { waits.push(ms); time += ms; }, waits,
    request: async (url, options) => { time += 10; return { latencyMs: 10, ...await send(url, options) }; } };
}
test('config has finite hard bounds and rejects unknown knobs or invalid origins', () => {
  assert.throws(() => configCheck({ ...c, logicalOperations: 100000 }));
  assert.throws(() => configCheck({ ...c, maxAttempts: 5 }));
  assert.throws(() => configCheck({ ...c, typo: true }));
  assert.throws(() => configCheck({ ...c, targetBaseUrl: 'http://localhost/path' }));
  assert.throws(() => configCheck({ ...c, arrivalIntervalMs: 10000 }));
});
test('comparison has equivalent schedules and seats but distinct retained identities', () => {
  const a = operations(c, manifest, 'immediate'), b = operations(c, manifest, 'jitter');
  assert.deepEqual(a.map(o => [o.scheduledMs, o.payload.seatNumber]), b.map(o => [o.scheduledMs, o.payload.seatNumber]));
  assert.notEqual(a[0].key, b[0].key); assert.notEqual(a[0].payload.eventId, b[0].payload.eventId);
  assert.throws(() => manifestCheck({ ...manifest, events: { immediate: op.payload.eventId, jitter: op.payload.eventId } }, c));
});
test('seeded jitter repeats and stays in exponential capped windows', () => {
  const x = random(2), y = random(2);
  for (let i = 1; i <= 4; i++) {
    const d = delayFor('jitter', i, c, x, 0);
    assert.equal(d, delayFor('jitter', i, c, y, 0));
    assert.ok(d >= 0 && d < Math.min(c.jitterCapMs, c.jitterBaseMs * 2 ** (i - 1)));
  }
});
test('immediate policy honors seconds and HTTP-date Retry-After floors', () => {
  assert.equal(retryAfter('1'), 1000);
  assert.equal(retryAfter('Thu, 01 Jan 1970 00:00:02 GMT', 1000), 1000);
  assert.equal(retryAfter('garbage'), 0);
  assert.equal(delayFor('immediate', 1, c, random(1), 1000), 1000);
});
test('transient retries preserve exact key/input and run one request at a time', async () => {
  let calls = 0, active = 0, peak = 0;
  const identities = [];
  const ctx = clock(async (_, options) => {
    active++; peak = Math.max(peak, active); identities.push([options.key, options.payload]);
    calls++; await Promise.resolve(); active--;
    return calls === 1 ? { status: 503, code: 'LAB_BUSY', retryAfterMs: 1000 } : { status: 201, data: booking(op) };
  });
  const result = await execute(op, 'immediate', c, ctx);
  assert.equal(result.outcome, 'RESOLVED'); assert.equal(result.attempts, 2);
  assert.equal(peak, 1); assert.deepEqual(identities[0], identities[1]); assert.deepEqual(ctx.waits, [1000]);
});
test('commit then response loss is discovered under the same booking identity', async () => {
  let calls = 0;
  const result = await execute(op, 'jitter', c, clock(async () => ++calls === 1 ?
    { status: 0, code: 'RESPONSE_LOSS' } : { status: 200, data: booking(op, true) }));
  assert.equal(result.outcome, 'RESOLVED'); assert.equal(result.replayed, true);
  assert.equal(result.bookingId, booking(op).id);
});
for (const [status, code] of [[409, 'SEAT_UNAVAILABLE'], [409, 'IDEMPOTENCY_CONFLICT'], [400, 'INVALID_REQUEST'], [404, 'NOT_FOUND']]) {
  test(`${status}/${code} is terminal without retry`, async () => {
    const result = await execute(op, 'jitter', c, clock(async () => ({ status, code })));
    assert.equal(result.attempts, 1); assert.equal(result.outcome, 'TERMINAL');
  });
}
test('attempt exhaustion reports unknown, never invented failure', async () => {
  const result = await execute(op, 'immediate', c, clock(async () => ({ status: 0 })));
  assert.equal(result.attempts, 4); assert.equal(result.outcome, 'UNRESOLVED');
});
test('known pre-transaction busy is reported separately from ambiguity', async () => {
  const result = await execute(op, 'immediate', c, clock(async () => ({ status: 503, code: 'LAB_BUSY' })));
  assert.equal(result.outcome, 'RETRY_EXHAUSTED');
});
test('delay beyond remaining deadline stops without another transmission', async () => {
  const ctx = clock(async () => ({ status: 503, code: 'DATABASE_UNAVAILABLE', retryAfterMs: 10000 }));
  const result = await execute(op, 'immediate', c, ctx);
  assert.equal(result.attempts, 1); assert.equal(result.outcome, 'UNRESOLVED'); assert.deepEqual(ctx.waits, []);
});
test('absolute deadline starts at scheduled arrival, including generator delay', async () => {
  const result = await execute(op, 'immediate', c, { now: () => 10001, start: 0, request: () => assert.fail('late send') });
  assert.equal(result.attempts, 0);
});
test('malformed success and terminal response after ambiguity stay unresolved', async () => {
  let calls = 0;
  const result = await execute(op, 'immediate', c, clock(async () => ++calls === 1 ?
    { status: 201, data: { id: 'invalid' } } : { status: 400, code: 'INVALID_REQUEST' }));
  assert.equal(result.outcome, 'UNRESOLVED'); assert.equal(result.attempts, 2);
});
test('write percentiles exclude fast inventory conflicts and replays', () => {
  const result = summarize([
    { outcome: 'RESOLVED', attempts: 2, logicalLatencyMs: 20, samples: [
      { status: 201, code: 'OK', second: 0, latencyMs: 30 }, { status: 200, code: 'OK', second: 1, latencyMs: 2, replayed: true }] },
    { outcome: 'TERMINAL', attempts: 1, samples: [{ status: 409, code: 'SEAT_UNAVAILABLE', second: 0, latencyMs: 1 }] }
  ]);
  assert.equal(result.successfulWriteLatencyMs.p99, 30); assert.equal(result.replays, 1);
  assert.equal(result.amplification, 1.5); assert.equal(result.perSecondHoldStatusRates[0]['409/SEAT_UNAVAILABLE'], 1);
});
test('generator saturation stops the arm without a hidden queue', async () => {
  const tiny = { ...c, logicalOperations: 3, arrivalIntervalMs: 1, maxConcurrentLogical: 1 };
  const report = await runArm(tiny, { ...manifest, config: tiny }, 'immediate', {
    send: async (_, options) => { await new Promise(resolve => setTimeout(resolve, 20)); return { status: 201, data: booking({ payload: options.payload }), latencyMs: 20 }; }
  });
  assert.equal(report.stopReason, 'GENERATOR_CONCURRENCY'); assert.equal(report.comparisonValid, false);
  assert.equal(report.summary.transmissions, 1); assert.equal(report.summary.outcomes.NOT_STARTED, 2);
});
test('business CLI refuses to run on the service host', () => {
  assert.throws(() => generator(c, 'abhishek'), /belongs on ankita/);
});
test('explicit local validation permits only small bounded loopback runs', () => {
  assert.doesNotThrow(() => generator({ ...c, targetBaseUrl: 'http://127.0.0.1:8134' }, 'abhishek', true));
  assert.throws(() => generator(c, 'abhishek', true), /Local validation/);
  assert.throws(() => generator({ ...c, targetBaseUrl: 'http://127.0.0.1:8134', logicalOperations: 21 }, 'abhishek', true));
  assert.throws(() => generator({ ...c, targetBaseUrl: 'http://127.0.0.1:8134', maxConcurrentLogical: 9 }, 'abhishek', true));
});

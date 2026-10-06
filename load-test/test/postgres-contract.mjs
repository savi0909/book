// Called only by HoldLoadClientIntegrationTest against its isolated PostgreSQL fixture.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { execute, operations, request } from '../lib.mjs';
import { createIngress } from '../ingress.mjs';

const backend = `http://127.0.0.1:${Number(process.argv[2])}`;
assert.match(process.argv[2], /^\d+$/);
const c = JSON.parse(fs.readFileSync(new URL('../config.small.json', import.meta.url)));
const events = {};
for (const arm of ['immediate', 'jitter']) {
  const r = await request(`${backend}/api/demo/events`, { payload: { name: `node-contract-${arm}`, seatCount: 10 } });
  assert.equal(r.status, 201); events[arm] = r.data.id;
}
const manifest = { version: 1, runId: '0123456789abcdef', config: c, events };
const ingress = createIngress(c, manifest, { backend, faults: true });
await new Promise(resolve => ingress.server.listen(0, '127.0.0.1', resolve));
const targetBaseUrl = `http://127.0.0.1:${ingress.server.address().port}`;
try {
  for (const arm of ['immediate', 'jitter']) {
    const op = operations(c, manifest, arm)[0]; // One pre-commit busy, then committed response loss.
    const result = await execute(op, arm, { ...c, targetBaseUrl });
    assert.equal(result.outcome, 'RESOLVED'); assert.equal(result.attempts, 3); assert.equal(result.replayed, true);
    const replay = await request(`${backend}/api/holds`, { payload: op.payload, key: op.key });
    assert.equal(replay.status, 200); assert.equal(replay.data.id, result.bookingId);
    assert.equal(replay.data.expiresAt, result.expiresAt); assert.equal(replay.data.createdAt, result.createdAt);
    const duplicates = await Promise.all(Array.from({ length: 4 }, () => request(`${backend}/api/holds`, { payload: op.payload, key: op.key })));
    assert.ok(duplicates.every(r => r.status === 200 && r.data.id === result.bookingId && r.data.expiresAt === result.expiresAt));
    const other = { ...op, key: `${op.key}-other`, payload: { ...op.payload, buyerId: `${op.payload.buyerId}-other` } };
    const conflict = await execute({ ...other, scheduledMs: 0 }, arm, { ...c, targetBaseUrl: backend });
    assert.equal(conflict.attempts, 1); assert.equal(conflict.samples[0].code, 'SEAT_UNAVAILABLE');
    const changed = await request(`${backend}/api/holds`, { key: op.key, payload: { ...op.payload, seatNumber: 2 } });
    assert.equal(changed.status, 409); assert.equal(changed.code, 'IDEMPOTENCY_CONFLICT');
  }
  assert.equal(ingress.metrics.lost, 2); assert.equal(ingress.metrics.busy, 2);
  console.log('Real PostgreSQL client contract: both policies discover one committed booking; unchanged deadlines, concurrent replay and terminal conflicts passed.');
} finally { await new Promise(resolve => { ingress.server.close(resolve); ingress.server.closeAllConnections(); }); }

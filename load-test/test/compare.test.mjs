import http from 'node:http';
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { runArm } from '../runner.mjs';
import { createIngress } from '../ingress.mjs';

test('repeated finite comparisons use distinct fixtures and preserve transmission bounds', async t => {
  const bookings = new Map();
  const backend = http.createServer(async (req, res) => {
    const chunks = []; for await (const chunk of req) chunks.push(chunk);
    const payload = JSON.parse(Buffer.concat(chunks)), key = req.headers['idempotency-key'];
    const replayed = bookings.has(key);
    if (!replayed) bookings.set(key, { ...payload, id: `00000000-0000-4000-8000-${String(bookings.size + 1).padStart(12, '0')}`,
      createdAt: '2026-10-06T00:00:00Z', expiresAt: '2026-10-06T00:02:00Z', state: 'HELD' });
    res.writeHead(replayed ? 200 : 201); res.end(JSON.stringify({ ...bookings.get(key), replayed }));
  });
  await new Promise(resolve => backend.listen(0, '127.0.0.1', resolve));
  t.after(() => new Promise(resolve => { backend.close(resolve); backend.closeAllConnections(); }));
  const c = { ...JSON.parse(fs.readFileSync(new URL('../config.small.json', import.meta.url))),
    logicalOperations: 4, arrivalIntervalMs: 20, maxConcurrentLogical: 8,
    jitterBaseMs: 2, jitterCapMs: 8, busyRetryAfterSeconds: 0 };
  for (let repeat = 0; repeat < 3; repeat++) {
    const manifest = { version: 1, runId: String(repeat).padStart(16, '0'), config: c,
      events: { immediate: `00000000-0000-4000-8000-${String(repeat * 2 + 1).padStart(12, '0')}`,
        jitter: `00000000-0000-4000-8000-${String(repeat * 2 + 2).padStart(12, '0')}` } };
    const ingress = createIngress(c, manifest, { backend: `http://127.0.0.1:${backend.address().port}`, faults: true });
    await new Promise(resolve => ingress.server.listen(0, '127.0.0.1', resolve));
    try {
      const runConfig = { ...c, targetBaseUrl: `http://127.0.0.1:${ingress.server.address().port}` };
      for (const arm of repeat % 2 ? ['jitter', 'immediate'] : ['immediate', 'jitter']) {
        const report = await runArm(runConfig, manifest, arm);
        assert.equal(report.comparisonValid, true);
        assert.equal(report.summary.outcomes.RESOLVED, 4);
        assert.equal(report.summary.transmissions, 7); // 4 initial + 2 busy + 1 lost commit.
        assert.ok(report.summary.transmissions <= c.logicalOperations * c.maxAttempts);
        assert.equal(report.summary.replays, 1);
      }
    } finally { await new Promise(resolve => { ingress.server.close(resolve); ingress.server.closeAllConnections(); }); }
  }
  assert.equal(bookings.size, 24);
});

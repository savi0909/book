import http from 'node:http';
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { request, execute, operations } from '../lib.mjs';
import { createIngress, privateBind } from '../ingress.mjs';

const c = JSON.parse(fs.readFileSync(new URL('../config.small.json', import.meta.url)));
const manifest = { version: 1, runId: '0123456789abcdef', config: c,
  events: { immediate: '00000000-0000-4000-8000-000000000001', jitter: '00000000-0000-4000-8000-000000000002' } };
async function listen(t, server) {
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(() => new Promise(resolve => { server.close(resolve); server.closeAllConnections(); }));
  return `http://127.0.0.1:${server.address().port}`;
}
test('absolute transport timeout includes incomplete response bodies', async t => {
  let calls = 0;
  const base = await listen(t, http.createServer((req, res) => { calls++; res.writeHead(201); res.write('{'); }));
  const r = await request(base, { timeoutMs: 50 });
  assert.equal(r.status, 0); assert.equal(r.code, 'TIMEOUT'); assert.equal(calls, 1);
});
test('redirect is not followed and bodies are size bounded', async t => {
  let calls = 0;
  const base = await listen(t, http.createServer((req, res) => {
    calls++;
    if (req.url === '/redirect') { res.writeHead(307, { Location: '/other' }); res.end(); }
    else { res.writeHead(200); res.end('x'.repeat(70000)); }
  }));
  assert.equal((await request(`${base}/redirect`)).status, 307); assert.equal(calls, 1);
  assert.equal((await request(`${base}/big`)).code, 'BODY_LIMIT');
});
test('only loopback and Tailscale IPv4 binds are accepted', () => {
  for (const bind of ['0.0.0.0', '::', '192.168.1.1', '100.128.0.1', '100.80.999.1']) assert.equal(privateBind(bind), false);
  assert.equal(privateBind('100.103.238.2'), true); assert.equal(privateBind('127.0.0.1'), true);
});
test('scoped ingress rejects other routes, tokens and fixtures', async t => {
  const token = 'secret'.repeat(8), ingress = createIngress(c, manifest, { token });
  const base = await listen(t, ingress.server), op = operations(c, manifest, 'immediate')[0];
  assert.equal((await request(`${base}/api/demo/events`, { token })).status, 404);
  assert.equal((await request(`${base}/api/holds`, { key: op.key, payload: op.payload })).status, 403);
  assert.equal((await request(`${base}/api/holds`, { token, key: 'arbitrary', payload: op.payload })).status, 429);
  assert.equal((await request(`${base}/api/holds`, { token, key: op.key, payload: { ...op.payload, seatNumber: 199 } })).status, 400);
  assert.equal(ingress.metrics.forwarded, 0);
});
test('fault ingress loses only a committed response; same-key replay is stable', async t => {
  const stored = new Map();
  let writes = 0;
  const backend = await listen(t, http.createServer(async (req, res) => {
    const chunks = []; for await (const chunk of req) chunks.push(chunk);
    const p = JSON.parse(Buffer.concat(chunks)), key = req.headers['idempotency-key'];
    const replayed = stored.has(key);
    if (!replayed) { writes++; stored.set(key, { ...p, id: '00000000-0000-4000-8000-000000000003', state: 'HELD',
      createdAt: '2026-10-06T00:00:00Z', expiresAt: '2026-10-06T00:02:00Z' }); }
    res.writeHead(replayed ? 200 : 201, { 'Content-Type': 'application/json' }); res.end(JSON.stringify({ ...stored.get(key), replayed }));
  }));
  const ingress = createIngress({ ...c, busyEvery: 0 }, manifest, { backend, faults: true });
  const base = await listen(t, ingress.server), op = operations(c, manifest, 'immediate')[0];
  const r = await execute(op, 'immediate', { ...c, targetBaseUrl: base });
  assert.equal(r.outcome, 'RESOLVED'); assert.equal(r.attempts, 2); assert.equal(r.replayed, true);
  assert.equal(writes, 1); assert.equal(ingress.metrics.lost, 1);
  assert.equal(r.expiresAt, stored.get(op.key).expiresAt);
});
test('fault controls default off', async t => {
  let calls = 0;
  const backend = await listen(t, http.createServer((req, res) => { calls++; req.resume(); res.writeHead(503); res.end('{"code":"DATABASE_UNAVAILABLE"}'); }));
  const ingress = createIngress(c, manifest, { backend });
  const base = await listen(t, ingress.server), op = operations(c, manifest, 'immediate')[0];
  assert.equal((await request(`${base}/api/holds`, { key: op.key, payload: op.payload })).code, 'DATABASE_UNAVAILABLE');
  assert.equal(calls, 1); assert.equal(ingress.metrics.lost, 0); assert.equal(ingress.metrics.busy, 0);
});

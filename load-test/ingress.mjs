import http from 'node:http';
import { timingSafeEqual } from 'node:crypto';
import { arms, operations, request } from './lib.mjs';

export function privateBind(ip) {
  if (ip === '127.0.0.1') return true;
  const octets = ip.split('.').map(Number);
  return octets.length === 4 && octets.every(n => Number.isInteger(n) && n >= 0 && n <= 255) &&
    octets[0] === 100 && octets[1] >= 64 && octets[1] <= 127;
}
export function createIngress(c, manifest, { backend = 'http://127.0.0.1:8132', token, faults = false } = {}) {
  const allowed = new Map(arms.flatMap(arm => operations(c, manifest, arm).map(op => [op.key, op])));
  const attempts = new Map(), lost = new Set();
  const metrics = { received: 0, forwarded: 0, busy: 0, lost: 0, active: 0, rejected: 0 };
  const cap = 2 * c.logicalOperations * (c.maxAttempts + c.discoveryAttempts);
  const reply = (res, status, code) => { res.writeHead(status, { 'Content-Type': 'application/json', ...(status === 503 ? { 'Retry-After': '1' } : {}) }); res.end(JSON.stringify({ code })); };
  const server = http.createServer(async (req, res) => {
    req.on('error', () => {});
    res.on('error', () => {});
    const provided = String(req.headers['x-hold-lab-token'] ?? '');
    if (token && (Buffer.byteLength(provided) !== Buffer.byteLength(token) || !timingSafeEqual(Buffer.from(provided), Buffer.from(token)))) {
      req.resume(); return reply(res, 403, 'FORBIDDEN');
    }
    if (req.method !== 'POST' || req.url !== '/api/holds') { req.resume(); return reply(res, 404, 'NOT_FOUND'); }
    const key = String(req.headers['idempotency-key'] ?? '');
    const op = allowed.get(key);
    if (!op || metrics.received >= cap) { req.resume(); metrics.rejected++; return reply(res, 429, 'INGRESS_SCOPE_OR_BUDGET'); }
    metrics.received++;
    if (metrics.active >= 32) { req.resume(); metrics.busy++; return reply(res, 503, 'INGRESS_BUSY'); }
    metrics.active++;
    try {
      let bytes = 0;
      const chunks = [];
      for await (const chunk of req) {
        bytes += chunk.length;
        if (bytes > 1024) { reply(res, 413, 'BODY_LIMIT'); req.destroy(); return; }
        chunks.push(chunk);
      }
      let payload;
      try { payload = JSON.parse(Buffer.concat(chunks).toString('utf8')); } catch { return reply(res, 400, 'INVALID_REQUEST'); }
      if (!payload || Object.keys(payload).length !== 4 || Object.entries(op.payload).some(([k, v]) => payload[k] !== v)) return reply(res, 400, 'FIXTURE_SCOPE');
      const attempt = (attempts.get(key) ?? 0) + 1;
      attempts.set(key, attempt);
      if (faults && c.busyEvery && op.index % c.busyEvery === 0 && attempt <= c.busyAttempts) {
        metrics.busy++;
        res.writeHead(503, { 'Content-Type': 'application/json', 'Retry-After': String(c.busyRetryAfterSeconds) });
        return res.end(JSON.stringify({ code: 'LAB_BUSY' }));
      }
      metrics.forwarded++;
      const r = await request(`${backend}/api/holds`, { payload, key, timeoutMs: 5000 });
      // Upstream 201 has arrived in full after BookingService.hold committed.
      // Destroy only the downstream response; never sleep with inventory locks.
      if (faults && r.status === 201 && c.lossEvery && op.index % c.lossEvery === 0 && !lost.has(key)) {
        lost.add(key); metrics.lost++; res.destroy(); return;
      }
      if (!r.status) return reply(res, 502, 'UPSTREAM_AMBIGUOUS');
      res.writeHead(r.status, { 'Content-Type': 'application/json', ...(r.retryAfterMs ? { 'Retry-After': String(Math.ceil(r.retryAfterMs / 1000)) } : {}) });
      res.end(JSON.stringify(r.data ?? { code: 'UPSTREAM_INVALID_BODY' }));
    } catch { if (!res.destroyed && !res.writableEnded) reply(res, 502, 'UPSTREAM_AMBIGUOUS'); }
    finally { metrics.active--; }
  });
  server.requestTimeout = 6000;
  server.headersTimeout = 5000;
  server.timeout = 6000;
  server.maxConnections = 32;
  return { server, metrics };
}

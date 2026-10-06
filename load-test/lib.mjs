import http from 'node:http';
import https from 'node:https';
import { performance } from 'node:perf_hooks';

export const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
export const uuid = value => typeof value === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value);
export const arms = ['immediate', 'jitter'];
export function configCheck(c) {
  const bounds = {
    seed: [0, 4294967295], logicalOperations: [1, 200], seatCount: [1, 200],
    arrivalIntervalMs: [1, 10000], ttlSeconds: [2, 120], maxAttempts: [1, 4],
    deadlineMs: [100, 10000], requestTimeoutMs: [50, 5000], minimumAttemptMs: [1, 1000],
    jitterBaseMs: [1, 2000], jitterCapMs: [1, 2000], maxConcurrentLogical: [1, 32],
    maxRunMs: [100, 120000], cooldownMs: [0, 10000], maxRssMiB: [32, 1024],
    maxCpuPercent: [1, 100], maxEventLoopLagMs: [10, 5000],
    maxHostCpuPercent: [1, 100], maxHostMemoryPercent: [1, 100],
    maxConsecutiveTransient: [1, 100], discoveryAttempts: [1, 2],
    busyEvery: [0, 200], busyAttempts: [0, 3], busyRetryAfterSeconds: [0, 2], lossEvery: [0, 200]
  };
  for (const [key, [min, max]] of Object.entries(bounds)) {
    if (!Number.isInteger(c[key]) || c[key] < min || c[key] > max) throw new Error(`${key} must be an integer in ${min}..${max}`);
  }
  for (const key of Object.keys(c)) if (!(key in bounds) && !['generatorHost', 'fixtureBaseUrl', 'targetBaseUrl'].includes(key)) throw new Error(`Unknown config field: ${key}`);
  if (typeof c.generatorHost !== 'string' || !/^[A-Za-z0-9-]{1,63}$/.test(c.generatorHost)) throw new Error('generatorHost is required');
  for (const key of ['fixtureBaseUrl', 'targetBaseUrl']) baseUrl(c[key]);
  if (c.requestTimeoutMs < c.minimumAttemptMs || c.deadlineMs < c.minimumAttemptMs || c.jitterCapMs < c.jitterBaseMs) throw new Error('Inconsistent deadline/jitter bounds');
  if ((c.logicalOperations - 1) * c.arrivalIntervalMs + c.deadlineMs > c.maxRunMs) throw new Error('Arrival schedule plus deadline exceeds maxRunMs');
  return c;
}
export function baseUrl(value) {
  const u = new URL(value);
  if (!['http:', 'https:'].includes(u.protocol) || u.username || u.password || u.pathname !== '/' || u.search || u.hash) throw new Error('Use an HTTP(S) origin without credentials/path/query');
  return u.origin;
}
export function random(seed) {
  let state = seed >>> 0;
  return () => { state = (Math.imul(1664525, state) + 1013904223) >>> 0; return state / 4294967296; };
}
export function retryAfter(value, now = Date.now()) {
  if (value == null) return 0;
  if (/^\d+$/.test(value)) return Number(value) * 1000;
  const time = Date.parse(value);
  return Number.isFinite(time) ? Math.max(0, time - now) : 0;
}
export function delayFor(mode, attempt, c, rng, floorMs) {
  return Math.max(floorMs, mode === 'immediate' ? 0 : Math.floor(rng() * Math.min(c.jitterCapMs, c.jitterBaseMs * 2 ** (attempt - 1))));
}
// One HTTP transmission, including a bounded body read; no redirects or transport retries.
export function request(url, { method = 'POST', payload, key, token, timeoutMs = 3500 } = {}) {
  const start = performance.now();
  const body = payload === undefined ? undefined : JSON.stringify(payload);
  return new Promise(resolve => {
    let settled = false;
    const finish = result => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      resolve({ ...result, latencyMs: performance.now() - start });
    };
    const req = (new URL(url).protocol === 'https:' ? https : http).request(url, {
      method, agent: false,
      headers: { ...(body ? { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body) } : {}),
        ...(key ? { 'Idempotency-Key': key } : {}), ...(token ? { 'X-Hold-Lab-Token': token } : {}) }
    }, res => {
      const chunks = [];
      let bytes = 0;
      res.on('data', chunk => {
        bytes += chunk.length;
        if (bytes > 65536) { req.destroy(); finish({ status: 0, code: 'BODY_LIMIT' }); }
        else chunks.push(chunk);
      });
      res.on('end', () => {
        let data;
        try { data = JSON.parse(Buffer.concat(chunks).toString('utf8')); } catch { /* Never retain raw bodies. */ }
        finish({ status: res.statusCode, data, retryAfterMs: retryAfter(res.headers['retry-after']),
          code: /^[A-Z_0-9]{1,64}$/.test(data?.code ?? '') ? data.code : undefined });
      });
      res.on('error', () => finish({ status: 0, code: 'RESPONSE_LOSS' }));
      res.on('aborted', () => finish({ status: 0, code: 'RESPONSE_LOSS' }));
    });
    const timer = setTimeout(() => { finish({ status: 0, code: 'TIMEOUT' }); req.destroy(); }, timeoutMs);
    req.on('error', () => finish({ status: 0, code: 'TRANSPORT_ERROR' }));
    req.end(body);
  });
}
export function operations(c, manifest, arm) {
  return Array.from({ length: c.logicalOperations }, (_, i) => ({
    index: i, scheduledMs: i * c.arrivalIntervalMs,
    key: `hold:${manifest.runId}:${arm}:${i}`,
    payload: { eventId: manifest.events[arm], seatNumber: i % c.seatCount + 1,
      buyerId: `load-${manifest.runId}-${arm}-${i}`, ttlSeconds: c.ttlSeconds }
  }));
}
export function manifestCheck(m, c) {
  if (m.version !== 1 || !/^[a-f0-9]{16}$/.test(m.runId) || !arms.every(a => uuid(m.events?.[a])) || m.events.immediate === m.events.jitter) throw new Error('Invalid/distinct fixture identities');
  if (JSON.stringify(m.config) !== JSON.stringify(c)) throw new Error('Use the exact configuration retained in the fixture manifest');
  return m;
}
function bookingMatches(data, op) {
  return uuid(data?.id) && data.eventId === op.payload.eventId && data.buyerId === op.payload.buyerId &&
    data.seatNumber === op.payload.seatNumber && typeof data.replayed === 'boolean' &&
    ['HELD', 'CHECKOUT', 'CONFIRMED', 'EXPIRED', 'CANCELLED', 'PAYMENT_FAILED'].includes(data.state) &&
    Number.isFinite(Date.parse(data.expiresAt)) && Number.isFinite(Date.parse(data.createdAt));
}
export async function execute(op, mode, c, context = {}) {
  const now = context.now ?? (() => performance.now());
  const wait = context.sleep ?? sleep;
  const send = context.request ?? request;
  const start = context.start ?? now();
  const due = start + op.scheduledMs;
  const deadline = Math.min(due + c.deadlineMs, context.hardDeadline ?? Infinity);
  const rng = random((c.seed ^ Math.imul(op.index + 1, 2654435761)) >>> 0);
  const samples = [];
  let ambiguous = false;
  let outcome = 'RETRY_EXHAUSTED';
  for (let attempt = 1; attempt <= c.maxAttempts; attempt++) {
    const remaining = deadline - now();
    if (context.stopped?.() || remaining < c.minimumAttemptMs) break;
    const sentAt = now();
    context.beforeAttempt?.(op, attempt);
    const r = await send(`${c.targetBaseUrl}/api/holds`, { payload: op.payload, key: op.key,
      token: context.token, timeoutMs: Math.min(c.requestTimeoutMs, remaining) });
    const valid = [200, 201].includes(r.status) && bookingMatches(r.data, op) && r.data.replayed === (r.status === 200);
    samples.push({ attempt, second: Math.floor((sentAt - start) / 1000), route: 'POST /api/holds',
      status: r.status, code: r.code ?? (valid ? 'OK' : 'UNCLASSIFIED'), latencyMs: r.latencyMs,
      replayed: valid && r.data.replayed });
    context.observe?.(r);
    if (valid) return { ...op, outcome: 'RESOLVED', attempts: samples.length, samples,
      bookingId: r.data.id, expiresAt: r.data.expiresAt, createdAt: r.data.createdAt, state: r.data.state,
      replayed: r.data.replayed, logicalLatencyMs: now() - due };
    const transient = [0, 408, 429, 500, 502, 503, 504].includes(r.status) || [200, 201].includes(r.status);
    if (!transient) { outcome = ambiguous ? 'UNRESOLVED' : 'TERMINAL'; break; }
    if (![429].includes(r.status) && r.code !== 'LAB_BUSY' && r.code !== 'INGRESS_BUSY') ambiguous = true;
    if (attempt === c.maxAttempts) break;
    const delay = delayFor(mode, attempt, c, rng, r.retryAfterMs ?? 0);
    if (delay + c.minimumAttemptMs > deadline - now()) break;
    await wait(delay);
  }
  return { ...op, outcome: !samples.length ? 'NOT_STARTED' : ambiguous ? 'UNRESOLVED' : outcome, attempts: samples.length, samples, logicalLatencyMs: now() - due };
}
export function distribution(values) {
  if (!values.length) return { count: 0, average: null, p50: null, p95: null, p99: null };
  const sorted = [...values].sort((a, b) => a - b);
  const percentile = p => sorted[Math.max(0, Math.ceil(sorted.length * p) - 1)];
  return { count: sorted.length, average: values.reduce((a, b) => a + b, 0) / values.length,
    p50: percentile(.5), p95: percentile(.95), p99: percentile(.99) };
}
export function summarize(results) {
  const samples = results.flatMap(r => r.samples);
  const counts = {}, rates = {}, attemptsPerOperation = {}, outcomes = {};
  for (const r of results) {
    outcomes[r.outcome] = (outcomes[r.outcome] ?? 0) + 1;
    attemptsPerOperation[r.attempts] = (attemptsPerOperation[r.attempts] ?? 0) + 1;
  }
  for (const s of samples) {
    const key = `${s.status}/${s.code}`;
    counts[key] = (counts[key] ?? 0) + 1;
    const perSecond = rates[s.second] ??= {};
    perSecond[key] = (perSecond[key] ?? 0) + 1;
  }
  return { logicalOperations: results.length, startedOperations: results.filter(r => r.attempts).length,
    transmissions: samples.length, amplification: samples.length / results.length,
    replays: samples.filter(s => s.replayed).length, outcomes, statusCounts: counts, attemptsPerOperation,
    perSecondHoldStatusRates: rates, allHttpLatencyMs: distribution(samples.map(s => s.latencyMs)),
    successfulWriteLatencyMs: distribution(samples.filter(s => s.status === 201 && s.code === 'OK').map(s => s.latencyMs)),
    discoveredReplayLatencyMs: distribution(samples.filter(s => s.replayed).map(s => s.latencyMs)),
    resolvedLogicalLatencyMs: distribution(results.filter(r => r.outcome === 'RESOLVED').map(r => r.logicalLatencyMs)) };
}

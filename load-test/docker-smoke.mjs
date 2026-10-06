// Explicit local Docker smoke: 100 fresh holds at 10/s, one arm, no retries/faults.
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { randomBytes } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { configCheck, request, uuid } from './lib.mjs';
import { runArm } from './runner.mjs';

function journal(file, record) {
  const fd = fs.openSync(file, 'a');
  try { fs.writeSync(fd, JSON.stringify(record) + '\n'); fs.fsyncSync(fd); }
  finally { fs.closeSync(fd); }
}
export async function smoke() {
  const name = process.env.SMOKE_RUN_NAME, control = process.env.SMOKE_CONTROL_FILE;
  if (!/^[a-z0-9][a-z0-9._-]{1,64}$/.test(name ?? '')) throw new Error('Use a fresh SMOKE_RUN_NAME without directory separators');
  if (os.hostname() !== 'book-my-show-loader') throw new Error('Run using the separate load-test Compose project');
  if (!control || !path.resolve(control).startsWith('/results/')) throw new Error('Control file must be under mounted /results');
  const heartbeat = `${control}.heartbeat`;
  if (fs.existsSync(control) || !fs.existsSync(heartbeat) || Date.now() - fs.statSync(heartbeat).mtimeMs > 15000)
    throw new Error('Start the metadata observer first; require its fresh heartbeat and no STOP marker');
  const dir = path.join('/results', name);
  fs.mkdirSync(dir); // An existing directory is never reused.
  const write = (file, value) => fs.writeFileSync(path.join(dir, file), JSON.stringify(value, null, 2) + '\n');
  const c = configCheck({ ...JSON.parse(fs.readFileSync(new URL('./config.small.json', import.meta.url))),
    generatorHost: 'book-my-show-loader', fixtureBaseUrl: 'http://api-a:8105', targetBaseUrl: 'http://gateway:8080',
    logicalOperations: 100, seatCount: 100, arrivalIntervalMs: 100, maxAttempts: 1,
    busyEvery: 0, busyAttempts: 0, lossEvery: 0, cooldownMs: 0 });
  const manifest = { version: 1, runId: randomBytes(8).toString('hex'), createdAt: new Date().toISOString(), config: c, events: {} };
  write('fixtures.json', manifest);
  // Keep the existing two-fixture manifest format; only immediate is used in this single-arm smoke.
  for (const arm of ['immediate', 'jitter']) {
    const r = await request(`${c.fixtureBaseUrl}/api/demo/events`, {
      payload: { name: `docker-smoke-${manifest.runId}-${arm}`, seatCount: c.seatCount }, timeoutMs: 5000
    });
    if (r.status !== 201 || !uuid(r.data?.id)) throw new Error(`Fixture creation ${arm} uncertain/rejected (${r.status}); partial manifest retained; do not auto-retry`);
    manifest.events[arm] = r.data.id; write('fixtures.json', manifest);
  }
  write('run.json', { version: 1, runId: manifest.runId, config: c, createdAt: new Date().toISOString(),
    generatorHost: os.hostname(), nodeVersion: process.version, execution: 'user-authorized-local-docker-smoke',
    timing: 'Docker client to gateway over booking bridge; metadata only', phase: 'single-arm-smoke',
    offeredRequestsPerSecond: 10, offeredWindowSeconds: 10, requestCap: 100, setupRequests: 2 });
  const abort = new AbortController();
  const stop = () => abort.abort();
  process.once('SIGINT', stop); process.once('SIGTERM', stop);
  const watcher = setInterval(() => {
    if (fs.existsSync(control) || !fs.existsSync(heartbeat) || Date.now() - fs.statSync(heartbeat).mtimeMs > 15000) stop();
  }, 250);
  try {
    const report = await runArm(c, manifest, 'immediate', { signal: abort.signal,
      onAttempt: (op, attempt) => journal(path.join(dir, 'immediate.sent.jsonl'), { index: op.index, attempt }),
      onSample: r => { const { payload, ...metadata } = r; fs.appendFileSync(path.join(dir, 'immediate.checkpoint.jsonl'), JSON.stringify(metadata) + '\n'); }
    });
    report.results = report.results.map(({ payload, ...metadata }) => metadata);
    report.execution = 'user-authorized-local-docker-smoke';
    report.offeredWindowSeconds = 10; report.offeredRequestsPerSecond = 10;
    write('immediate.json', report);
    console.log(JSON.stringify({ runId: manifest.runId, output: dir, elapsedMs: report.elapsedMs,
      ...report.summary, stopReason: report.stopReason }));
    if (report.stopReason || report.summary.outcomes.RESOLVED !== 100 || report.summary.statusCounts['201/OK'] !== 100) process.exitCode = 2;
  } finally { clearInterval(watcher); }
}
if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url))
  smoke().catch(error => { console.error(error.message); process.exitCode = 1; });

import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { randomBytes } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { arms, configCheck, manifestCheck, operations, request, sleep, summarize, uuid } from './lib.mjs';
import { runArm } from './runner.mjs';
import { createIngress, privateBind } from './ingress.mjs';

function args(argv) {
  const result = { command: argv[0] };
  for (let i = 1; i < argv.length; i++) {
    if (!argv[i].startsWith('--')) throw new Error('Use --option value');
    const name = argv[i].slice(2);
    if (result[name] !== undefined) throw new Error(`Duplicate --${name}`);
    result[name] = ['enable-faults', 'local-validation'].includes(name) ? true : argv[++i];
    if (result[name] === undefined) throw new Error(`Missing --${name} value`);
  }
  return result;
}
function read(file) { if (!file) throw new Error('Required file argument missing'); return JSON.parse(fs.readFileSync(file, 'utf8')); }
function outputDir(dir) {
  if (!dir) throw new Error('Use --out NEW_DIRECTORY (never reuse an earlier run)');
  fs.mkdirSync(path.dirname(path.resolve(dir)), { recursive: true });
  fs.mkdirSync(dir); return dir;
}
function write(dir, name, value) { fs.writeFileSync(path.join(dir, name), JSON.stringify(value, null, 2) + '\n'); }
function journal(file, value) {
  const fd = fs.openSync(file, 'a');
  try { fs.writeSync(fd, JSON.stringify(value) + '\n'); fs.fsyncSync(fd); }
  finally { fs.closeSync(fd); }
}
function lines(file) {
  if (!fs.existsSync(file)) return [];
  const text = fs.readFileSync(file, 'utf8');
  // A partial final record after a hard process crash is not an acknowledged outcome.
  const complete = text.endsWith('\n') ? text : text.slice(0, text.lastIndexOf('\n') + 1);
  return complete.split('\n').filter(Boolean).map(line => JSON.parse(line));
}
// Retain selected identifiers/timing, not request or response bodies.
function metadata(r) { const { payload, ...rest } = r; return rest; }
export function generator(c, host = os.hostname(), localValidation = false) {
  if (localValidation) {
    if (new URL(c.targetBaseUrl).hostname !== '127.0.0.1' || c.logicalOperations > 20 ||
      c.maxConcurrentLogical > 8 || c.maxRunMs > 30000) throw new Error('Local validation requires loopback ingress, <=20 operations/arm, <=8 concurrent and <=30s/arm');
    return;
  }
  if (host.toLowerCase() !== c.generatorHost.toLowerCase()) throw new Error(`Business run/discovery belongs on ${c.generatorHost}; this host is ${host}`);
  const targetHost = new URL(c.targetBaseUrl).hostname;
  if (targetHost === '127.0.0.1' || !privateBind(targetHost)) throw new Error('Ankita must use Abhishek\'s Tailscale IPv4 ingress');
  if (!process.env.HOLD_LAB_TOKEN || process.env.HOLD_LAB_TOKEN.length < 32) throw new Error('Set HOLD_LAB_TOKEN to the ingress token (at least 32 characters)');
}
const help = `Run from repository root; Node >=22, no npm install required.
  node load-test/cli.mjs prepare --config load-test/config.small.json --out load-test/results/FIXTURES
  node load-test/cli.mjs plan --fixtures load-test/results/FIXTURES/fixtures.json
  node load-test/cli.mjs ingress --fixtures FIXTURES_JSON --bind TAILSCALE_IPV4 --port 8134 --lifetime-seconds 300 --stop-file FRESH_STOP_FILE [--enable-faults]
  node load-test/cli.mjs run --fixtures FIXTURES_JSON --out NEW_RUN_DIRECTORY [--first-arm immediate|jitter] [--local-validation]
  node load-test/cli.mjs discover --fixtures FIXTURES_JSON --run RUN_DIRECTORY --out NEW_DISCOVERY_DIRECTORY [--local-validation]
See docs/HOLD_LOAD_TEST_TUTORIAL.md. prepare creates two retained events; final run/discover require Ankita.
--local-validation permits only small loopback functionality checks authorized by the user.
Ingress requires HOLD_LAB_TOKEN on a tailnet bind; fault injection is OFF unless explicitly enabled.`;
export async function main(argv) {
  const a = args(argv);
  if (!a.command || a.command === 'help') { console.log(help); return; }
  const options = {
    prepare: ['config', 'out'], plan: ['fixtures'],
    ingress: ['fixtures', 'bind', 'port', 'lifetime-seconds', 'stop-file', 'enable-faults'],
    run: ['fixtures', 'out', 'first-arm', 'local-validation'], discover: ['fixtures', 'run', 'out', 'local-validation']
  }[a.command];
  if (!options) throw new Error('Unknown command; use help');
  for (const key of Object.keys(a)) if (key !== 'command' && !options.includes(key)) throw new Error(`Unknown --${key}`);
  if (a.command === 'prepare') {
    const c = configCheck(read(a.config)), out = outputDir(a.out);
    if (!['localhost', '127.0.0.1', '[::1]'].includes(new URL(c.fixtureBaseUrl).hostname)) throw new Error('Fixture controls remain on loopback; prepare on Abhishek');
    const manifest = { version: 1, runId: randomBytes(8).toString('hex'), createdAt: new Date().toISOString(), config: c, events: {} };
    write(out, 'fixtures.json', manifest);
    for (const arm of arms) {
      const r = await request(`${c.fixtureBaseUrl}/api/demo/events`, {
        payload: { name: `hold-load-${manifest.runId}-${arm}`, seatCount: c.seatCount }, timeoutMs: 5000
      });
      if (r.status !== 201 || !uuid(r.data?.id)) throw new Error(`Fixture ${arm} creation uncertain or rejected (${r.status}); do not retry automatically. Inspect retained events; partial manifest saved.`);
      manifest.events[arm] = r.data.id;
      write(out, 'fixtures.json', manifest);
    }
    console.log(`Prepared two distinct retained events. Transfer ${path.join(out, 'fixtures.json')} to Ankita.`);
    return;
  }
  const manifest = read(a.fixtures), c = configCheck(manifest.config);
  manifestCheck(manifest, c);
  if (a.command === 'plan') {
    console.log(JSON.stringify({ runId: manifest.runId, target: c.targetBaseUrl, generator: c.generatorHost,
      logicalOperationsPerArm: c.logicalOperations, policyTransmissionCapBothArms: 2 * c.logicalOperations * c.maxAttempts,
      discoveryTransmissionCapBothArms: 2 * c.logicalOperations * c.discoveryAttempts,
      scheduleMs: (c.logicalOperations - 1) * c.arrivalIntervalMs, maxRunMsPerArm: c.maxRunMs,
      maxConcurrentLogical: c.maxConcurrentLogical, immutableFixtureIds: manifest.events }, null, 2));
    return;
  }
  if (a.command === 'ingress') {
    if (!privateBind(a.bind ?? '')) throw new Error('--bind must be this host\'s Tailscale IPv4 or 127.0.0.1; public/wildcard binds forbidden');
    const port = Number(a.port), lifetime = Number(a['lifetime-seconds']);
    if (!Number.isInteger(port) || port < 1024 || port > 65535 || !Number.isInteger(lifetime) || lifetime < 10 || lifetime > 600) throw new Error('Require --port 1024..65535 and --lifetime-seconds 10..600');
    const token = process.env.HOLD_LAB_TOKEN;
    if (a.bind !== '127.0.0.1' && (!token || token.length < 32)) throw new Error('Tailnet ingress requires HOLD_LAB_TOKEN of at least 32 characters');
    if (!a['stop-file'] || fs.existsSync(a['stop-file'])) throw new Error('Require --stop-file with a fresh path, shared with the observer');
    const heartbeat = `${a['stop-file']}.heartbeat`;
    if (!fs.existsSync(heartbeat) || Date.now() - fs.statSync(heartbeat).mtimeMs > 15000) throw new Error('Start the observer first; require a heartbeat less than 15 seconds old');
    const ingress = createIngress(c, manifest, { token, faults: !!a['enable-faults'] });
    await new Promise((resolve, reject) => { ingress.server.once('error', reject); ingress.server.listen(port, a.bind, resolve); });
    console.log(JSON.stringify({ bind: a.bind, port, lifetimeSeconds: lifetime, faults: !!a['enable-faults'], runId: manifest.runId }));
    const close = () => { ingress.server.close(); ingress.server.closeAllConnections(); };
    const timer = setTimeout(close, lifetime * 1000);
    const stopWatch = setInterval(() => {
      if (fs.existsSync(a['stop-file']) || !fs.existsSync(heartbeat) || Date.now() - fs.statSync(heartbeat).mtimeMs > 15000) close();
    }, 250);
    process.once('SIGINT', close); process.once('SIGTERM', close);
    await new Promise(resolve => ingress.server.once('close', resolve));
    clearTimeout(timer);
    clearInterval(stopWatch);
    console.log(JSON.stringify({ ingressMetadata: ingress.metrics }));
    return;
  }
  generator(c, os.hostname(), !!a['local-validation']);
  const out = outputDir(a.out), abort = new AbortController();
  process.once('SIGINT', () => abort.abort()); process.once('SIGTERM', () => abort.abort());
  write(out, 'run.json', { version: 1, runId: manifest.runId, config: c, createdAt: new Date().toISOString(),
    generatorHost: os.hostname(), execution: a['local-validation'] ? 'local-bounded-validation' : 'Ankita-business-run',
    timing: a['local-validation'] ? 'local loopback end-to-end; metadata only' : 'Ankita end-to-end; metadata only', phase: a.command });
  const token = process.env.HOLD_LAB_TOKEN;
  if (a.command === 'run') {
    const first = a['first-arm'] ?? 'immediate';
    if (!arms.includes(first)) throw new Error('--first-arm must be immediate or jitter');
    const reports = [];
    for (const arm of [first, arms.find(value => value !== first)]) {
      // Exclusive local marker prevents accidental replay-only repeat runs with this fixture.
      const marker = `${path.resolve(a.fixtures)}.${arm}.started`;
      fs.writeFileSync(marker, JSON.stringify({ out: path.resolve(out), startedAt: new Date().toISOString() }), { flag: 'wx' });
      const report = await runArm(c, manifest, arm, { token, signal: abort.signal,
        onAttempt: (op, attempt) => journal(path.join(out, `${arm}.sent.jsonl`), { index: op.index, attempt }),
        onSample: r => fs.appendFileSync(path.join(out, `${arm}.checkpoint.jsonl`), JSON.stringify(metadata(r)) + '\n') });
      report.results = report.results.map(metadata);
      write(out, `${arm}.json`, report); reports.push(report);
      console.log(JSON.stringify({ arm, ...report.summary, stopReason: report.stopReason }));
      if (report.stopReason || abort.signal.aborted) break;
      await sleep(c.cooldownMs);
    }
    write(out, 'comparison.json', { runId: manifest.runId,
      valid: reports.length === 2 && reports.every(r => r.comparisonValid),
      reports: reports.map(({ results, resources, ...r }) => r),
      execution: a['local-validation'] ? 'local-bounded-validation' : 'executed on configured generator host' });
    if (reports.length !== 2 || reports.some(r => r.stopReason)) process.exitCode = 2;
  } else {
    const run = read(path.join(a.run ?? '', 'run.json'));
    if (run.runId !== manifest.runId || JSON.stringify(run.config) !== JSON.stringify(c)) throw new Error('Discovery requires matching run and fixture configuration');
    for (const arm of arms) {
      let prior;
      const report = path.join(a.run, `${arm}.json`), checkpoint = path.join(a.run, `${arm}.checkpoint.jsonl`), journalFile = path.join(a.run, `${arm}.sent.jsonl`);
      try { prior = read(report).results; if (!Array.isArray(prior)) throw new Error('Incomplete report'); }
      catch { prior = lines(checkpoint); }
      const known = new Map(prior.map(r => [r.index, r]));
      // A hard crash may leave a request in flight without a completed checkpoint.
      const sent = new Set(lines(journalFile).map(line => line.index));
      const candidates = operations(c, manifest, arm).filter(op => known.get(op.index)?.outcome === 'UNRESOLVED' || (sent.has(op.index) && !known.has(op.index)));
      if (!candidates.length) { write(out, `${arm}.json`, { arm, phase: 'discovery', results: [], summary: null }); continue; }
      fs.writeFileSync(`${path.resolve(a.fixtures)}.${arm}.discovery.started`, JSON.stringify({ out: path.resolve(out) }), { flag: 'wx' });
      const discovery = await runArm({ ...c, maxAttempts: c.discoveryAttempts }, manifest, 'jitter', {
        token, signal: abort.signal, selectedOperations: candidates.map((op, i) => ({ ...op, scheduledMs: i * c.arrivalIntervalMs })),
        onAttempt: (op, attempt) => journal(path.join(out, `${arm}.sent.jsonl`), { index: op.index, attempt }),
        onSample: r => fs.appendFileSync(path.join(out, `${arm}.checkpoint.jsonl`), JSON.stringify(metadata({ ...r, outcome: r.outcome === 'RESOLVED' ? 'RESOLVED' : 'UNRESOLVED' })) + '\n')
      });
      discovery.arm = arm; discovery.phase = 'discovery';
      discovery.results = discovery.results.map(r => metadata({ ...r, outcome: r.outcome === 'RESOLVED' ? 'RESOLVED' : 'UNRESOLVED' }));
      discovery.summary = summarize(discovery.results);
      write(out, `${arm}.json`, discovery);
      if (discovery.results.some(r => r.outcome === 'UNRESOLVED') || discovery.stopReason || abort.signal.aborted) process.exitCode = 2;
    }
  }
}
if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main(process.argv.slice(2)).catch(error => { console.error(error.message); process.exitCode = 1; });
}

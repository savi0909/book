// Metadata observer for Abhishek. No business HTTP requests or SQL query-text capture.
import fs from 'node:fs';
import path from 'node:path';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { performance } from 'node:perf_hooks';
import { sleep } from './lib.mjs';
import { hostMetrics, hostSnapshot } from './resources.mjs';

const exec = promisify(execFile);
export const dbPressureSql = `SELECT json_build_object('connections',count(*),
  'active',count(*) FILTER (WHERE state='active'),
  'lockWaits',count(*) FILTER (WHERE wait_event_type='Lock'))
  FROM pg_stat_activity WHERE datname='booking' AND pid<>pg_backend_pid()`;
const compose = ['compose', '-p', 'sd-book-my-show', '-f', 'compose.yml', '-f', 'compose.failover.yml'];
export function serverThreshold(sample) {
  const resources = sample.containers ?? [];
  if (resources.some(row => parseFloat(row.CPUPerc) > 90)) return 'SERVER_CPU';
  if (resources.some(row => parseFloat(row.MemPerc) > 85)) return 'SERVER_MEMORY';
  if ((sample.database?.connections ?? 0) > 20) return 'SERVER_CONNECTIONS';
  if ((sample.database?.lockWaits ?? 0) > 4) return 'SERVER_LOCK_WAITS';
  if (sample.error) return 'OBSERVER_UNAVAILABLE';
  if (sample.hostCpuPercent > 90) return 'SERVER_HOST_CPU';
  if (sample.hostMemoryUsedPercent > 90) return 'SERVER_HOST_MEMORY';
  return null;
}
export async function observe(dir, seconds, stopFile) {
  if (!Number.isInteger(seconds) || seconds < 10 || seconds > 900) throw new Error('duration-seconds must be 10..900');
  fs.mkdirSync(path.dirname(path.resolve(dir)), { recursive: true }); fs.mkdirSync(dir);
  if (fs.existsSync(stopFile) || fs.existsSync(`${stopFile}.heartbeat`)) throw new Error('Use a fresh stop-file path');
  fs.mkdirSync(path.dirname(path.resolve(stopFile)), { recursive: true });
  const { stdout } = await exec('docker', [...compose, 'ps', '-q'], { timeout: 5000 });
  const ids = stdout.trim().split(/\s+/).filter(Boolean);
  if (ids.length !== 4) throw new Error('Expected the normal four-service sd-book-my-show stack; inspect Compose state first');
  const start = performance.now();
  let previousHost = hostSnapshot();
  let breaches = 0, stopReason;
  while (performance.now() - start < seconds * 1000) {
    const sampleStart = performance.now();
    let sample;
    try {
      const [stats, pressure] = await Promise.all([
        exec('docker', ['stats', '--no-stream', '--format', '{{json .}}', ...ids], { timeout: 5000 }),
        exec('docker', [...compose, 'exec', '-T', '-e', 'PGCONNECT_TIMEOUT=1', '-e', 'PGOPTIONS=-c statement_timeout=1000',
          'postgres', 'psql', '-U', 'booking_demo', '-d', 'booking', '-At', '-c', dbPressureSql], { timeout: 5000 })
      ]);
      const rows = stats.stdout.trim().split(/\r?\n/).filter(Boolean).map(JSON.parse);
      if (rows.length !== 4 || rows.some(row => !Number.isFinite(parseFloat(row.CPUPerc)) || !Number.isFinite(parseFloat(row.MemPerc)))) throw new Error('Incomplete Docker resource observation');
      sample = { at: new Date().toISOString(), elapsedMs: performance.now() - start,
        containers: rows.map(({ Name, CPUPerc, MemUsage, MemPerc, PIDs }) => ({ Name, CPUPerc, MemUsage, MemPerc, PIDs })),
        database: JSON.parse(pressure.stdout.trim()), poolWaitMetrics: 'not exposed by current application' };
    } catch { sample = { at: new Date().toISOString(), elapsedMs: performance.now() - start, error: 'OBSERVER_UNAVAILABLE' }; }
    const currentHost = hostSnapshot();
    Object.assign(sample, hostMetrics(previousHost, currentHost));
    previousHost = currentHost;
    const threshold = serverThreshold(sample);
    breaches = threshold ? breaches + 1 : 0;
    fs.appendFileSync(path.join(dir, 'server-metadata.jsonl'), JSON.stringify({ ...sample, threshold }) + '\n');
    fs.writeFileSync(`${stopFile}.heartbeat`, new Date().toISOString());
    if (!fs.existsSync(path.join(dir, 'ready.json'))) {
      fs.writeFileSync(path.join(dir, 'ready.json'), JSON.stringify({ heartbeat: `${stopFile}.heartbeat` }));
      console.log(JSON.stringify({ observerReady: true, heartbeat: `${stopFile}.heartbeat` }));
    }
    if (breaches >= 3) {
      stopReason = threshold;
      fs.writeFileSync(stopFile, JSON.stringify({ stopReason, at: new Date().toISOString() }), { flag: 'wx' });
      break;
    }
    await sleep(Math.max(0, 1000 - (performance.now() - sampleStart)));
  }
  if (!fs.existsSync(stopFile)) fs.writeFileSync(stopFile, JSON.stringify({ stopReason: 'OBSERVER_FINISHED', at: new Date().toISOString() }), { flag: 'wx' });
  fs.writeFileSync(path.join(dir, 'summary.json'), JSON.stringify({ seconds, elapsedMs: performance.now() - start,
    stopReason: stopReason ?? null, requestedCadenceMs: 1000, actualCadence: 'recorded per sample; docker stats may take longer' }, null, 2));
  console.log(JSON.stringify({ observerFinished: true, stopReason: stopReason ?? null }));
}
if (process.argv[1]?.endsWith('observe.mjs')) {
  const [dir, seconds, stopFile] = process.argv.slice(2);
  if (!dir || !stopFile) { console.error('node load-test/observe.mjs NEW_OUTPUT_DIR DURATION_SECONDS FRESH_STOP_FILE'); process.exitCode = 1; }
  else observe(dir, Number(seconds), stopFile).catch(error => { console.error(error.message); process.exitCode = 1; });
}

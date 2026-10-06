import { performance } from 'node:perf_hooks';
import { execute, operations, sleep, summarize } from './lib.mjs';
import { hostMetrics, hostSnapshot } from './resources.mjs';

export async function runArm(c, manifest, arm, { token, signal, send, selectedOperations, onAttempt = () => {}, onSample = () => {} } = {}) {
  const start = performance.now();
  const inFlight = new Set();
  const results = [];
  const resources = [];
  let stopReason;
  let transientStreak = 0;
  let previousCpu = process.cpuUsage();
  let previousTick = start;
  let previousHost = hostSnapshot();
  const stop = reason => { stopReason ??= reason; };
  const stopped = () => { if (signal?.aborted) stop('INTERRUPTED'); return !!stopReason; };
  const watchdog = setTimeout(() => stop('RUN_DEADLINE'), c.maxRunMs);
  const monitor = setInterval(() => {
    const time = performance.now(), interval = time - previousTick;
    const usage = process.cpuUsage(), rssMiB = process.memoryUsage().rss / 1048576;
    const cpuPercent = ((usage.user - previousCpu.user) + (usage.system - previousCpu.system)) / (interval * 10);
    const eventLoopLagMs = Math.max(0, interval - 1000);
    const currentHost = hostSnapshot(), host = hostMetrics(previousHost, currentHost);
    resources.push({ second: Math.floor((time - start) / 1000), cpuPercent, rssMiB, eventLoopLagMs, activeLogical: inFlight.size, ...host });
    if (rssMiB > c.maxRssMiB) stop('GENERATOR_MEMORY');
    if (cpuPercent > c.maxCpuPercent) stop('GENERATOR_CPU');
    if (eventLoopLagMs > c.maxEventLoopLagMs) stop('GENERATOR_EVENT_LOOP');
    if (host.hostCpuPercent > c.maxHostCpuPercent) stop('GENERATOR_HOST_CPU');
    if (host.hostMemoryUsedPercent > c.maxHostMemoryPercent) stop('GENERATOR_HOST_MEMORY');
    if (time - start >= c.maxRunMs) stop('RUN_DEADLINE');
    previousCpu = usage; previousTick = time;
    previousHost = currentHost;
  }, 1000);
  try {
    for (const op of selectedOperations ?? operations(c, manifest, arm)) {
      // Poll bounded waits so Ctrl+C does not wait for a distant arrival.
      while (!stopped() && performance.now() < start + op.scheduledMs) await sleep(Math.min(50, start + op.scheduledMs - performance.now()));
      if (stopped() || inFlight.size >= c.maxConcurrentLogical) {
        if (!stopped()) stop('GENERATOR_CONCURRENCY');
        results.push({ ...op, outcome: 'NOT_STARTED', attempts: 0, samples: [], logicalLatencyMs: 0 });
        continue;
      }
      const task = execute(op, arm, c, { start, hardDeadline: start + c.maxRunMs, stopped, token,
        beforeAttempt: onAttempt, ...(send ? { request: send } : {}),
        observe: r => {
          transientStreak = [0, 408, 429, 500, 502, 503, 504].includes(r.status) ? transientStreak + 1 : 0;
          if (transientStreak >= c.maxConsecutiveTransient) stop('PERSISTENT_TRANSIENT');
        }
      }).then(r => { results.push(r); onSample(r); }).finally(() => inFlight.delete(task));
      inFlight.add(task);
    }
    await Promise.all(inFlight);
  } finally { clearInterval(monitor); clearTimeout(watchdog); }
  results.sort((a, b) => a.index - b.index);
  return { arm, startedAt: new Date(Date.now() - (performance.now() - start)).toISOString(), elapsedMs: performance.now() - start,
    stopReason: stopReason ?? null, comparisonValid: !stopReason, summary: summarize(results), resources, results };
}

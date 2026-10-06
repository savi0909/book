import os from 'node:os';

export function hostSnapshot() {
  const times = os.cpus().reduce((sum, cpu) => {
    sum.idle += cpu.times.idle;
    sum.total += Object.values(cpu.times).reduce((a, b) => a + b, 0);
    return sum;
  }, { idle: 0, total: 0 });
  return { ...times, memoryUsedPercent: (1 - os.freemem() / os.totalmem()) * 100 };
}
export function hostMetrics(previous, current) {
  const elapsed = current.total - previous.total;
  return { hostCpuPercent: elapsed > 0 ? (1 - (current.idle - previous.idle) / elapsed) * 100 : null,
    hostMemoryUsedPercent: current.memoryUsedPercent };
}

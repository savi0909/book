import test from 'node:test';
import assert from 'node:assert/strict';
import { dbPressureSql, serverThreshold } from '../observe.mjs';

test('observer collects aggregate pressure without query text', () => {
  assert.ok(!dbPressureSql.includes('query'));
  assert.equal(serverThreshold({ containers: [{ CPUPerc: '30%', MemPerc: '40%' }], database: { connections: 8, lockWaits: 0 } }), null);
});
test('server thresholds stop for CPU, memory, DB pressure or unavailable observation', () => {
  assert.equal(serverThreshold({ containers: [{ CPUPerc: '91%' }] }), 'SERVER_CPU');
  assert.equal(serverThreshold({ containers: [{ MemPerc: '86%' }] }), 'SERVER_MEMORY');
  assert.equal(serverThreshold({ database: { connections: 21 } }), 'SERVER_CONNECTIONS');
  assert.equal(serverThreshold({ database: { lockWaits: 5 } }), 'SERVER_LOCK_WAITS');
  assert.equal(serverThreshold({ error: 'OBSERVER_UNAVAILABLE' }), 'OBSERVER_UNAVAILABLE');
  assert.equal(serverThreshold({ hostCpuPercent: 91 }), 'SERVER_HOST_CPU');
  assert.equal(serverThreshold({ hostMemoryUsedPercent: 91 }), 'SERVER_HOST_MEMORY');
});

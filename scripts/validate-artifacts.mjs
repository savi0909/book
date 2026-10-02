import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import assert from 'node:assert/strict';

const project = process.cwd();
const reference = 'D:/AA-SYSTEM-DESIGN-ARCHITECTURE/sep-30-2026/sdir-p-main';
const required = ['pom.xml', 'README.md', 'AGENTS.md', 'CLAUDE.md', 'PARITY.md',
  'docs/SYSTEM_SPEC.md', 'docs/USER_GUIDE.md', 'docs/API_REFERENCE.md',
  'docs/INTERVIEW_GUIDE.md', 'docs/VERIFICATION.md', 'Dockerfile', 'compose.yml',
  'postman/ticket-booking-lab.postman_collection.json', 'postman/local.postman_environment.json',
  'src/main/resources/db/migration/V1__booking.sql', 'src/test/java/com/example/booking/BookingIntegrationTest.java',
  'compose.failover.yml', 'infra/haproxy.cfg', 'docs/API_FAILOVER_TUTORIAL.md',
  'postman/failover.postman_collection.json', 'postman/failover.postman_environment.json',
  'scripts/learn-failover.mjs', 'src/test/java/com/example/booking/FailoverIntegrationTest.java',
  'compose.provider.yml', 'infra/ProviderStub.java', 'infra/Dockerfile.provider',
  'docs/PROVIDER_ISOLATION_TUTORIAL.md', 'postman/provider.postman_collection.json',
  'postman/provider.postman_environment.json', 'scripts/learn-provider.mjs',
  'src/test/java/com/example/booking/ProviderIsolationIntegrationTest.java',
  'src/main/resources/db/migration/V2__provider_retry_budget.sql',
  'src/main/resources/db/migration/V3__poison_job_isolation.sql', 'compose.poison.yml',
  'docs/POISON_JOB_TUTORIAL.md', 'postman/poison.postman_collection.json',
  'postman/poison.postman_environment.json', 'scripts/learn-poison.mjs',
  'scripts/generate-poison-postman.mjs', 'src/test/java/com/example/booking/PoisonIsolationIntegrationTest.java',
  'src/main/java/com/example/booking/RecoveryIsolation.java',
  'src/main/java/com/example/booking/PoisonController.java',
  'src/main/java/com/example/booking/RecoveryController.java',
  'src/main/resources/db/migration/V4__transactional_outbox.sql', 'compose.outbox.yml',
  'docs/TRANSACTIONAL_OUTBOX_TUTORIAL.md', 'postman/outbox.postman_collection.json',
  'postman/outbox.postman_environment.json', 'scripts/learn-outbox.mjs',
  'scripts/generate-outbox-postman.mjs', 'src/test/java/com/example/booking/OutboxIntegrationTest.java',
  'src/main/java/com/example/booking/OutboxDispatcher.java', 'src/main/java/com/example/booking/OutboxWorker.java',
  'src/main/java/com/example/booking/LocalNotificationSink.java', 'src/main/java/com/example/booking/DeliveryController.java',
  'src/main/java/com/example/booking/OutboxController.java'];
for (const name of required) assert.ok(fs.existsSync(path.join(project, name)), name);
const collection = JSON.parse(fs.readFileSync(required[12], 'utf8'));
const environment = JSON.parse(fs.readFileSync(required[13], 'utf8'));
assert.equal(collection.info.schema, 'https://schema.getpostman.com/json/collection/v2.1.0/collection.json');
assert.ok(environment.values.some(v => v.key === 'baseUrl' && v.enabled));
const requests = [];
let scriptBlocks = 0;
function inspect(node) {
  for (const event of node.event ?? []) { new Function(event.script.exec.join('\n')); scriptBlocks++; }
  if (node.request) requests.push(node);
  for (const item of node.item ?? []) inspect(item);
}
inspect(collection);
const apiOneCollection = JSON.parse(fs.readFileSync('postman/api-01-create-event.postman_collection.json', 'utf8'));
assert.equal(apiOneCollection.info.schema, collection.info.schema);
assert.equal(apiOneCollection.item.length, 1, 'API 1 collection stays focused on one request');
assert.equal(apiOneCollection.item[0].request.method, 'POST');
assert.equal(apiOneCollection.item[0].request.url, '{{baseUrl}}/api/demo/events');
assert.ok(apiOneCollection.variable.some(v => v.key === 'baseUrl' && v.value === 'http://localhost:8105'));
assert.deepEqual(JSON.parse(apiOneCollection.item[0].request.body.raw), { name: 'My first concert', seatCount: 3 });
inspect(apiOneCollection);
const failoverCollection = JSON.parse(fs.readFileSync('postman/failover.postman_collection.json', 'utf8'));
const failoverEnvironment = JSON.parse(fs.readFileSync('postman/failover.postman_environment.json', 'utf8'));
assert.equal(failoverCollection.info.schema, collection.info.schema);
for (const name of ['baseUrl', 'apiA', 'apiB']) assert.ok(failoverEnvironment.values.some(v => v.key === name && v.enabled));
inspect(failoverCollection);
const providerCollection = JSON.parse(fs.readFileSync('postman/provider.postman_collection.json', 'utf8'));
const providerEnvironment = JSON.parse(fs.readFileSync('postman/provider.postman_environment.json', 'utf8'));
assert.equal(providerCollection.info.schema, collection.info.schema);
for (const name of ['baseUrl', 'providerUrl']) assert.ok(providerEnvironment.values.some(v => v.key === name && v.enabled));
inspect(providerCollection);
const poisonCollection = JSON.parse(fs.readFileSync('postman/poison.postman_collection.json', 'utf8'));
const poisonEnvironment = JSON.parse(fs.readFileSync('postman/poison.postman_environment.json', 'utf8'));
assert.equal(poisonCollection.info.schema, collection.info.schema);
assert.ok(poisonEnvironment.values.some(v => v.key === 'baseUrl' && v.enabled));
inspect(poisonCollection);
const outboxCollection = JSON.parse(fs.readFileSync('postman/outbox.postman_collection.json', 'utf8'));
const outboxEnvironment = JSON.parse(fs.readFileSync('postman/outbox.postman_environment.json', 'utf8'));
assert.equal(outboxCollection.info.schema, collection.info.schema);
for(const name of ['baseUrl','apiB','gatewayUrl']) assert.ok(outboxEnvironment.values.some(v=>v.key===name&&v.enabled));
inspect(outboxCollection);
assert.ok(requests.every(r => r.name && r.event.some(e => e.listen === 'test')), 'named requests have assertions');
const projectMarkdown = execFileSync('rg', ['--files', project, '-g', '*.md'], { encoding: 'utf8' }).trim().split(/\r?\n/);
const shared = ['AGENTS.md', 'memory/PROJECT_CONTEXT.md', 'memory/PROGRESS.md', 'memory/DECISIONS.md',
  'docs/learning/README.md', 'docs/learning/PROJECT_STATUS.md', 'docs/learning/CASE_STUDY_INDEX.md',
  'docs/learning/INTERVIEW_PRACTICE_PLAN.md', 'docs/learning/cases/Ticket_Booking.md',
  ...['01_CHAT_SYSTEM', '02_URL_SHORTENER', '03_NEWS_FEED', '04_NOTIFICATION_SYSTEM',
    '05_TICKET_BOOKING', 'RESUME_ONE_PROJECT', 'INTERVIEW_PRACTICE_ONLY'].map(n => `docs/learning/handovers/${n}.md`)
].map(n => path.join(reference, n));
const markdown = [...projectMarkdown, ...shared, 'D:/java-projects/AGENTS.md'];
let links = 0;
const broken = [];
for (const file of markdown) {
  let content = fs.readFileSync(file, 'utf8').replace(/```[\s\S]*?```/g, '');
  for (const match of content.matchAll(/\[[^\]]*\]\(([^)]+)\)/g)) {
    const target = match[1].replace(/^<|>$/g, '').split('#')[0];
    if (!target || /^(https?:|mailto:|app:)/.test(target)) continue;
    links++;
    const resolved = path.resolve(path.dirname(file), decodeURIComponent(target));
    if (!fs.existsSync(resolved)) broken.push({ file, target });
  }
}
assert.deepEqual(broken, [], 'local Markdown link targets');
const evidence = { requiredFiles: required.length, namedRequests: requests.length, scriptBlocks,
  markdownFiles: markdown.length, localLinks: links, brokenLinks: broken.length };
fs.writeFileSync('target/artifact-evidence.json', JSON.stringify(evidence, null, 2));
console.log(JSON.stringify(evidence, null, 2));

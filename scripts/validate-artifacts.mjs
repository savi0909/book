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
  'scripts/learn-failover.mjs', 'src/test/java/com/example/booking/FailoverIntegrationTest.java'];
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
const failoverCollection = JSON.parse(fs.readFileSync('postman/failover.postman_collection.json', 'utf8'));
const failoverEnvironment = JSON.parse(fs.readFileSync('postman/failover.postman_environment.json', 'utf8'));
assert.equal(failoverCollection.info.schema, collection.info.schema);
for (const name of ['baseUrl', 'apiA', 'apiB']) assert.ok(failoverEnvironment.values.some(v => v.key === name && v.enabled));
inspect(failoverCollection);
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

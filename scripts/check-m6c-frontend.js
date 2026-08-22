'use strict';

const assert = require('assert');
const fs = require('fs');
const vm = require('vm');

const root = process.cwd();
const read = relativePath => fs.readFileSync(root + '/' + relativePath, 'utf8');

function inlineScript(relativePath) {
  const html = read(relativePath);
  const scripts = [];
  const pattern = /<script>\s*([\s\S]*?)\s*<\/script>/g;
  let match;
  while ((match = pattern.exec(html)) !== null) scripts.push(match[1]);
  assert.strictEqual(scripts.length, 1, relativePath + ' must have one inline application script');
  new vm.Script(scripts[0], { filename: relativePath });
  return { html, script: scripts[0] };
}

function captureUser(script, socketHolder) {
  let component;
  const sandbox = {
    Vue: function Vue(options) { component = options; return options; },
    axios: { get: () => Promise.resolve({ data: [] }), post: () => Promise.resolve({ data: {} }) },
    token: 'm6c-user-token',
    Promise,
    console: { log() {}, warn() {}, error() {} },
    location: { protocol: 'http:', host: 'localhost', href: '' },
    history: { back() {} },
    sessionStorage: { setItem() {}, removeItem() {} },
    WebSocket: function WebSocket() {
      socketHolder.socket = this;
      this.readyState = 0;
      this.close = () => { this.readyState = 3; };
    },
    setTimeout() { return 1; },
    clearTimeout() {}
  };
  sandbox.WebSocket.CONNECTING = 0;
  sandbox.WebSocket.OPEN = 1;
  vm.runInNewContext(script, sandbox);
  assert(component, 'user Vue component was not captured');
  return component;
}

async function main() {
  const api = read('frontend/admin/src/api/index.js');
  const marketing = read('frontend/admin/src/views/Marketing.vue');
  for (const path of [
    '/admin/marketing/campaigns/${campaignId}/batch-jobs',
    '/admin/marketing/batch-jobs',
    '/admin/marketing/batch-jobs/${id}/items',
    '/admin/marketing/batch-jobs/${id}/pause',
    '/admin/marketing/batch-jobs/${id}/resume',
    '/admin/marketing/batch-jobs/${id}/retry-failures'
  ]) assert(api.includes(path), 'missing admin API path: ' + path);
  for (const status of ['GRANTED', 'IDEMPOTENT', 'SKIPPED', 'FAILED']) {
    assert(marketing.includes(status), 'missing batch status panel field: ' + status);
  }
  assert(marketing.includes('MANUAL_TAG') && marketing.includes("campaign.grantMode === 'ADMIN'"),
    'admin UI must gate creation to manual-tag ADMIN/BOTH campaigns');
  assert(marketing.includes('setTimeout(() => loadBatchJobs(true), 2000)'),
    'admin UI must use bounded refresh scheduling');
  assert(marketing.includes("job?.status === 'COMPLETED' || job?.status === 'PARTIAL_FAILED'"),
    'admin UI must stop polling at both terminal states');
  assert(!marketing.includes('setInterval('), 'admin UI must not use an unbounded interval poller');

  const userPage = inlineScript('frontend/user/info.html');
  assert(userPage.html.includes('label="券包"'), 'user page must expose the persisted grant list');
  assert(userPage.script.includes('/voucher-grants/mine'), 'user page must refresh persisted grants');
  assert(userPage.script.includes("message.type !== 'VOUCHER_GRANTED'"),
    'user page must filter the fixed notification type');
  assert(userPage.script.includes('seenGrantEvents'), 'user page must deduplicate eventId notifications');

  const socketHolder = {};
  const component = captureUser(userPage.script, socketHolder);
  const notifications = [];
  const gets = [];
  const state = Object.assign({}, component.data, component.methods, {
    $set(object, key, value) { object[key] = value; },
    $notify(value) { notifications.push(value); }
  });
  state.queryGrants = component.methods.queryGrants.bind(state);
  state.connectGrantNotifications();
  assert(socketHolder.socket, 'user page must open a grant notification WebSocket');
  socketHolder.socket.onmessage({ data: JSON.stringify({
    type: 'VOUCHER_GRANTED', eventId: 'event-1', message: '已到账'
  }) });
  socketHolder.socket.onmessage({ data: JSON.stringify({
    type: 'VOUCHER_GRANTED', eventId: 'event-1', message: '已到账'
  }) });
  await Promise.resolve();
  assert.strictEqual(notifications.length, 1, 'duplicate eventId must not duplicate the prompt');
  process.stdout.write('M6C frontend contracts: PASS\n');
}

main().catch(error => {
  console.error(error);
  process.exitCode = 1;
});

'use strict';

const assert = require('assert');
const fs = require('fs');
const vm = require('vm');

const root = process.cwd();

function read(relativePath) {
  return fs.readFileSync(root + '/' + relativePath, 'utf8');
}

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

function captureShop(script, axios) {
  let component;
  const sandbox = {
    Vue: function Vue(options) { component = options; return options; },
    axios,
    util: { getUrlParam: () => '1', formatPrice: value => String(value) },
    token: 'm6b-test-token',
    Promise,
    console: { log() {}, warn() {}, error() {} },
    location: { protocol: 'http:', host: 'localhost', href: '' },
    history: { back() {} },
    navigator: {},
    window: { addEventListener() {}, removeEventListener() {} },
    WebSocket: function WebSocket() {},
    setInterval() { return 1; },
    clearInterval() {},
    setTimeout() { return 1; },
    clearTimeout() {}
  };
  vm.runInNewContext(script, sandbox);
  assert(component, 'Vue component was not captured');
  return component;
}

function stateFor(component) {
  return Object.assign({}, component.data, component.methods, {
    $message: { success() {}, error() {} }
  });
}

async function main() {
  const page = inlineScript('frontend/user/shop-detail.html');
  assert(page.html.includes('每日签到奖励'), 'TASK campaign badge must be visible');
  assert(page.html.includes('签到并领取'), 'TASK campaign button must be visible');
  assert(page.script.includes('/user/sign'), 'TASK flow must sign before rewarding');
  assert(page.script.includes('/task-reward'), 'TASK flow must call task-reward endpoint');
  assert(!page.script.includes('idempotencyKey'), 'client must not construct idempotencyKey');
  assert(!page.script.includes('bizDate'), 'client must not construct bizDate');

  const calls = [];
  let failReward = true;
  const component = captureShop(page.script, {
    get() {
      return Promise.resolve({ data: [{ id: '7', claimState: 'CLAIMABLE' }] });
    },
    post(url, body) {
      calls.push({ url, body });
      if (url === '/user/sign') return Promise.resolve({ data: {} });
      if (failReward) {
        failReward = false;
        return Promise.reject('奖励暂不可用');
      }
      return Promise.resolve({ data: {} });
    }
  });
  const state = stateFor(component);
  const campaign = { id: '7', ruleVersion: '3', claimState: 'CLAIMABLE', grantMode: 'TASK' };

  const first = component.methods.claimCampaign.call(state, campaign);
  component.methods.claimCampaign.call(state, campaign);
  await first;
  assert.deepStrictEqual(calls.map(call => call.url), [
    '/user/sign', '/voucher-campaigns/7/task-reward'
  ], 'duplicate clicks must not duplicate the sign/reward flow');
  assert.deepStrictEqual(Object.keys(calls[1].body), ['expectedRuleVersion'],
    'TASK reward request must contain only expectedRuleVersion');
  assert.strictEqual(calls[1].body.expectedRuleVersion, '3');
  assert.strictEqual(state.claimingCampaignId, null, 'failed reward must release retry guard');

  await component.methods.claimCampaign.call(state, campaign);
  assert.deepStrictEqual(calls.map(call => call.url), [
    '/user/sign', '/voucher-campaigns/7/task-reward',
    '/user/sign', '/voucher-campaigns/7/task-reward'
  ], 'a failed reward must be retryable');
  assert.strictEqual(state.claimingCampaignId, null);
  process.stdout.write('M6B frontend contracts: PASS\n');
}

main().catch(error => {
  console.error(error);
  process.exitCode = 1;
});

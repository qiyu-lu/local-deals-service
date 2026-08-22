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

function captureShop(script, tokenValue, axios) {
  let component;
  const util = {
    getUrlParam: () => '1',
    formatPrice: value => String(value)
  };
  const sandbox = {
    Vue: function Vue(options) { component = options; return options; },
    axios,
    util,
    token: tokenValue,
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
  assert(!/v-else>\s*抢购\s*<\/div>/.test(page.html), 'ordinary voucher must not show misleading purchase text');
  assert(page.html.includes('普通代金券'), 'ordinary voucher note must remain static');

  const anonymousCalls = [];
  const anonymous = captureShop(page.script, '', {
    get(url) { anonymousCalls.push(url); return Promise.resolve({ data: [] }); },
    post() { throw new Error('anonymous must not claim'); }
  });
  await anonymous.methods.queryCampaigns.call(stateFor(anonymous), '1');
  assert.deepStrictEqual(anonymousCalls, [], 'anonymous page must not request campaign list');

  const authenticatedGets = [];
  const authenticated = captureShop(page.script, 'user-token', {
    get(url) {
      authenticatedGets.push(url);
      return Promise.resolve({ data: [{ id: '7', claimState: 'CLAIMABLE' }] });
    },
    post() { return Promise.resolve({ data: {} }); }
  });
  const authenticatedState = stateFor(authenticated);
  await authenticated.methods.queryCampaigns.call(authenticatedState, '1');
  assert(authenticatedGets.includes('/voucher-campaigns/shop/1'), 'authenticated page must load campaigns');

  let resolveClaim;
  let postCalls = 0;
  const claimGets = [];
  const claimPosts = [];
  const claiming = captureShop(page.script, 'user-token', {
    get(url) {
      claimGets.push(url);
      return Promise.resolve({ data: [{ id: '7', claimState: 'ALREADY_GRANTED' }] });
    },
    post(url, body) {
      postCalls++;
      claimPosts.push({ url, body });
      return new Promise(resolve => { resolveClaim = resolve; });
    }
  });
  const claimingState = stateFor(claiming);
  const campaign = { id: '7', ruleVersion: '2', claimState: 'CLAIMABLE' };
  const pending = claiming.methods.claimCampaign.call(claimingState, campaign);
  claiming.methods.claimCampaign.call(claimingState, campaign);
  assert.strictEqual(postCalls, 1, 'duplicate clicks must not create parallel claim requests');
  resolveClaim({ data: {} });
  await pending;
  assert.strictEqual(claimPosts.length, 1);
  assert.strictEqual(claimPosts[0].url, '/voucher-campaigns/7/claim');
  assert.strictEqual(claimPosts[0].body.expectedRuleVersion, '2');
  assert(claimGets.includes('/voucher-campaigns/shop/1'), 'successful claim must reload campaign state');
  assert.strictEqual(claimingState.campaigns[0].claimState, 'ALREADY_GRANTED');
  assert.strictEqual(claimingState.claimingCampaignId, null);

  let failureGets = 0;
  const failed = captureShop(page.script, 'user-token', {
    get() {
      failureGets++;
      return Promise.resolve({ data: [{ id: '7', claimState: 'CLAIMABLE' }] });
    },
    post() { return Promise.reject('额度已用尽'); }
  });
  const failedState = stateFor(failed);
  await failed.methods.claimCampaign.call(failedState, campaign);
  assert.strictEqual(failureGets, 1, 'failed claim must reload campaign state');
  assert.strictEqual(failedState.campaigns[0].claimState, 'CLAIMABLE',
    'failed claim must not assume success');
  assert.strictEqual(failedState.claimingCampaignId, null);
  process.stdout.write('M6A frontend contracts: PASS\n');
}

main().catch(error => {
  console.error(error);
  process.exitCode = 1;
});

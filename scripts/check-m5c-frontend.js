'use strict';

const assert = require('assert');
const fs = require('fs');
const vm = require('vm');

const root = process.cwd();
const quietConsole = { log() {}, warn() {}, error() {} };

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
  return scripts[0];
}

function captureVue(script, extras) {
  let component;
  const sandbox = Object.assign({
    Vue: function Vue(options) { component = options; return options; },
    console: quietConsole,
    Promise,
    util: { getUrlParam: () => '' },
    token: 'test-token',
    location: { protocol: 'http:', host: 'localhost', href: '' },
    history: { back() {} },
    navigator: {},
    window: { addEventListener() {}, removeEventListener() {} },
    WebSocket: function WebSocket() {},
    setInterval() { return 1; },
    clearInterval() {},
    setTimeout() { return 1; },
    clearTimeout() {}
  }, extras || {});
  vm.runInNewContext(script, sandbox);
  assert(component, 'Vue component was not captured');
  return component;
}

function flushPromises() {
  return new Promise(resolve => setImmediate(resolve));
}

async function expectRejected(promise, message) {
  try {
    await promise;
    assert.fail('expected rejection');
  } catch (error) {
    assert.strictEqual(error, message);
  }
}

async function checkCommonInterceptor() {
  let rejected;
  const axios = {
    defaults: {},
    interceptors: {
      request: { use() {} },
      response: { use(success, failure) { rejected = failure; } }
    }
  };
  vm.runInNewContext(read('frontend/user/js/common.js'), {
    axios,
    sessionStorage: { getItem: () => null },
    location: { href: '' },
    setTimeout() {},
    console: quietConsole,
    Promise,
    window: { location: { search: '' } },
    decodeURI
  });
  await expectRejected(rejected({
    response: { status: 429, data: { errorMsg: '请求过于频繁' } }
  }), '请求过于频繁');
  await expectRejected(rejected(new Error('offline')), '网络连接异常');
}

async function checkLoginCountdown() {
  const script = inlineScript('frontend/user/login.html');
  let intervals = 0;
  const failed = captureVue(script, {
    axios: { post: () => Promise.reject('发送受限') },
    setInterval() { intervals++; return 1; }
  });
  const failedState = Object.assign({}, failed.data, {
    form: { phone: '13800138000' },
    $message: { error() {} }
  });
  failed.methods.sendCode.call(failedState);
  await flushPromises();
  assert.strictEqual(intervals, 0, 'failed OTP send must not start countdown');
  assert.strictEqual(failedState.disabled, false);

  const succeeded = captureVue(script, {
    axios: { post: () => Promise.resolve({}) },
    setInterval() { intervals++; return 2; }
  });
  const successState = Object.assign({}, succeeded.data, {
    form: { phone: '13800138000' },
    $message: { error() {} }
  });
  succeeded.methods.sendCode.call(successState);
  await flushPromises();
  assert.strictEqual(intervals, 1, 'successful OTP send must start one countdown');
  assert.strictEqual(successState.disabled, true);
}

async function checkSeckillWatcher() {
  const script = inlineScript('frontend/user/shop-detail.html');
  let watcherCalls = 0;
  const failed = captureVue(script, {
    axios: {
      get: () => Promise.resolve({ data: {} }),
      post: () => Promise.reject('流量受限')
    }
  });
  const state = Object.assign({}, failed.data, failed.methods, {
    $message: function () {},
    listenSeckillResult() { watcherCalls++; }
  });
  state.$message.error = function () {};
  failed.methods.seckill.call(state, {
    id: 17,
    beginTime: new Date(Date.now() - 60_000).toISOString(),
    endTime: new Date(Date.now() + 60_000).toISOString(),
    stock: 1
  });
  await flushPromises();
  assert.strictEqual(watcherCalls, 0, 'rejected seckill must not create watcher');
}

async function checkSearchKeepsExistingRows() {
  const script = inlineScript('frontend/user/shop-list.html');
  const component = captureVue(script, {
    axios: { get: () => Promise.reject('搜索暂不可用') }
  });
  const existing = [{ id: 7, name: 'existing' }];
  const state = Object.assign({}, component.data, component.methods, {
    shops: existing,
    keyword: 'tea',
    params: Object.assign({}, component.data.params, { current: 1 }),
    $message: { error() {} },
    $nextTick() {}
  });
  component.methods.queryShopsByKeyword.call(state);
  await flushPromises();
  await flushPromises();
  assert.strictEqual(state.shops, existing, 'failed search must preserve displayed rows');
  assert.strictEqual(state.loading, false, 'failed search must finish loading');
}

(async function main() {
  new vm.Script(read('frontend/user/js/common.js'), { filename: 'common.js' });
  await checkCommonInterceptor();
  await checkLoginCountdown();
  await checkSeckillWatcher();
  await checkSearchKeepsExistingRows();
  process.stdout.write('M5C frontend contracts: PASS\n');
})().catch(error => {
  console.error(error);
  process.exitCode = 1;
});

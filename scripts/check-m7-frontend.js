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

function captureUser(script, socketHolder, get) {
  let component;
  const sandbox = {
    Vue: function Vue(options) { component = options; return options; },
    axios: { get, post: () => Promise.resolve({ data: {} }) },
    token: 'm7-user-token',
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
    setTimeout(callback) {
      socketHolder.timerCallbacks.push(callback);
      return socketHolder.timerCallbacks.length;
    },
    clearTimeout(timer) {
      socketHolder.clearedTimers.push(timer);
    }
  };
  sandbox.WebSocket.CONNECTING = 0;
  sandbox.WebSocket.OPEN = 1;
  vm.runInNewContext(script, sandbox);
  assert(component, 'user Vue component was not captured');
  return component;
}

function stateFor(component) {
  return Object.assign({}, component.data, component.methods, {
    $set(object, key, value) { object[key] = value; },
    $notify() {}
  });
}

async function main() {
  const page = inlineScript('frontend/user/info.html');
  assert(page.html.includes('label="券包"'), 'user page must expose the persisted grant list');
  assert(page.script.includes('socket.onclose') && page.script.includes('socket.onerror'),
    'WebSocket close and error handlers must be explicit');
  assert(page.script.includes('startGrantFallbackPoll') && page.script.includes('pollGrantFallback'),
    'WebSocket failures must enter the persistent grant fallback poller');
  assert(page.script.includes('grantPollAttempts >= 10') && page.script.includes('grantPollDeadline'),
    'fallback polling must have both a maximum attempt count and a deadline');
  assert(page.script.includes("normalized === 'SUCCESS'") && page.script.includes("normalized === 'FAILED'"),
    'fallback polling must stop at SUCCESS and FAILED');
  assert(!page.script.includes('setInterval('), 'user fallback must not use an unbounded interval');

  const socketHolder = { timerCallbacks: [], clearedTimers: [] };
  const gets = [];
  const component = captureUser(page.script, socketHolder, () => {
    gets.push(true);
    return Promise.resolve({ data: [] });
  });
  const state = stateFor(component);
  state.connectGrantNotifications();
  assert(socketHolder.socket, 'user page must open a grant notification WebSocket');
  socketHolder.socket.onclose();
  assert.strictEqual(state.grantPollState, 'POLLING', 'socket close must start fallback polling');
  assert.strictEqual(gets.length, 1, 'fallback polling must refresh the persistent grant list immediately');

  for (let i = 0; i < 9; i += 1) {
    state.pollGrantFallback();
    await Promise.resolve();
  }
  assert.strictEqual(gets.length, 10, 'fallback polling must make at most ten requests');
  assert.notStrictEqual(state.grantPollState, 'POLLING', 'fallback polling must stop after ten attempts');

  state.grantPollState = 'POLLING';
  state.grantPollAttempts = 0;
  state.grantPollDeadline = Date.now() - 1;
  state.pollGrantFallback();
  assert.strictEqual(gets.length, 10, 'deadline must prevent another fallback request');
  assert.notStrictEqual(state.grantPollState, 'POLLING', 'deadline must stop fallback polling');

  const terminalHolder = { timerCallbacks: [], clearedTimers: [] };
  const terminalComponent = captureUser(page.script, terminalHolder, () =>
    Promise.resolve({ data: { status: 'FAILED', list: [] } }));
  const terminalState = stateFor(terminalComponent);
  terminalState.startGrantFallbackPoll();
  await Promise.resolve();
  assert.strictEqual(terminalState.grantPollState, 'FAILED', 'FAILED response must stop fallback polling');
  assert.strictEqual(terminalState.grantPollTimer, null, 'terminal response must clear the fallback timer');

  process.stdout.write('M7 frontend contracts: PASS\n');
}

main().catch(error => {
  console.error(error);
  process.exitCode = 1;
});

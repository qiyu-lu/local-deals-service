// Open-model seckill admission load: requests arrive at RATE/s regardless of how fast the
// system answers (constant-arrival-rate), so latency growth and dropped iterations show the
// real knee instead of a closed-loop client slowing itself down.
//
//   k6 run -e BASE_URL=http://127.0.0.1:28083 -e VOUCHER_ID=42 -e RATE=2000 -e DURATION=30s \
//          -e TOKENS=/data/tokens.csv -e USER_OFFSET=0 seckill.js
import http from 'k6/http';
import { SharedArray } from 'k6/data';
import { Counter } from 'k6/metrics';
import exec from 'k6/execution';

const tokens = new SharedArray('tokens', () =>
  open(__ENV.TOKENS).split('\n').filter((line) => line.length > 0).map((line) => line.split(',')[0]));

const rate = parseInt(__ENV.RATE || '1000', 10);
const offset = parseInt(__ENV.USER_OFFSET || '0', 10);
// One counter per outcome: --summary-export drops tagged sub-metrics without thresholds.
const OUTCOMES = ['accepted', 'out_of_stock', 'duplicate', 'rejected_other',
  'http_429', 'http_503', 'http_5xx', 'http_other'];
const counters = Object.fromEntries(OUTCOMES.map((o) => [o, new Counter(`outcome_${o}`)]));

function classify(res) {
  if (res.status === 200) {
    try {
      const body = res.json();
      if (body.success) return 'accepted';
      if (body.code === 'SECKILL_OUT_OF_STOCK') return 'out_of_stock';
      if (body.code === 'SECKILL_DUPLICATE') return 'duplicate';
    } catch (e) {
      // fall through
    }
    return 'rejected_other';
  }
  if (res.status === 429) return 'http_429';
  if (res.status === 503) return 'http_503';
  if (res.status >= 500) return 'http_5xx';
  return 'http_other';
}

export const options = {
  discardResponseBodies: false,
  scenarios: {
    admission: {
      executor: 'constant-arrival-rate',
      rate,
      timeUnit: '1s',
      duration: __ENV.DURATION || '30s',
      preAllocatedVUs: parseInt(__ENV.PRE_VUS || String(Math.min(2000, Math.max(100, rate / 5))), 10),
      maxVUs: parseInt(__ENV.MAX_VUS || '4000', 10),
    },
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export default function () {
  // Each iteration is a different user until the token pool wraps around.
  const i = (offset + exec.scenario.iterationInTest) % tokens.length;
  const res = http.post(`${__ENV.BASE_URL}/voucher-order/seckill/${__ENV.VOUCHER_ID}`, null, {
    headers: { authorization: tokens[i] },
    timeout: __ENV.TIMEOUT || '10s',
  });
  counters[classify(res)].add(1);
}

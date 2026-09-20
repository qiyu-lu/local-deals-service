// The whole buyer lifecycle in one open-model run, for the multi-instance scenario:
// admission -> wait for the order to land in MySQL -> pay, or walk away.
//
//   k6 run -e BASE_URL=http://127.0.0.1:38983 -e VOUCHER_ID=42 -e RATE=20000 -e DURATION=6s \
//          -e TOKENS=/data/tokens.csv -e USER_OFFSET=0 -e SECKILL_TOKEN_SECRET=... \
//          -e PAY_SECRET=... -e PAY_RATIO=0.7 fullchain.js
//
// PAY_RATIO of the winners pay; the rest never come back, which is what the timeout close and
// the stock it returns are there for.
//
// The script plays the payment channel itself: it prepays through the API and then posts the
// signed callback the channel would post. The in-process mock channel keeps its payments in one
// JVM's memory, so its cashier only works on the instance that issued the prepay — a real
// channel is an external system, and this is the shape that matches the multi-instance case.
// The endpoint under test is the one that matters, /payment/callback: signed, repeated,
// unordered, and idempotent through the payment row.
import http from 'k6/http';
import { SharedArray } from 'k6/data';
import { Counter, Trend } from 'k6/metrics';
import exec from 'k6/execution';
import { sleep } from 'k6';
import { hmac } from 'k6/crypto';

const users = new SharedArray('tokens', () =>
  open(__ENV.TOKENS).split('\n').filter((line) => line.length > 0).map((line) => line.split(',')));
const seckillSecret = __ENV.SECKILL_TOKEN_SECRET || '';
const paySecret = __ENV.PAY_SECRET || '';
const tokenTtl = parseInt(__ENV.SECKILL_TOKEN_TTL || '600', 10);
const rate = parseInt(__ENV.RATE || '1000', 10);
const offset = parseInt(__ENV.USER_OFFSET || '0', 10);
const payRatio = parseFloat(__ENV.PAY_RATIO || '0.7');
// How long a winner waits for the consumer to persist the order before giving up on paying.
const pollAttempts = parseInt(__ENV.POLL_ATTEMPTS || '40', 10);
const pollInterval = parseFloat(__ENV.POLL_INTERVAL || '0.5');

const OUTCOMES = ['accepted', 'out_of_stock', 'duplicate', 'rejected_other',
  'http_429', 'http_503', 'http_5xx', 'http_other'];
const counters = Object.fromEntries(OUTCOMES.map((o) => [o, new Counter(`outcome_${o}`)]));
// What happened to the winners: these four have to add up to accepted.
const PHASES = ['paid', 'left_unpaid', 'never_persisted', 'pay_rejected', 'callback_failed'];
const phases = Object.fromEntries(PHASES.map((p) => [p, new Counter(`phase_${p}`)]));
const persistWait = new Trend('persist_wait_s');

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
    lifecycle: {
      executor: 'constant-arrival-rate',
      rate,
      timeUnit: '1s',
      duration: __ENV.DURATION || '30s',
      preAllocatedVUs: parseInt(__ENV.PRE_VUS || String(Math.min(2000, Math.max(200, rate / 4))), 10),
      maxVUs: parseInt(__ENV.MAX_VUS || '6000', 10),
      // A winner stays in its iteration polling and paying; the run is over when they are done.
      gracefulStop: __ENV.GRACEFUL_STOP || '60s',
    },
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

/** Same canonical form as PaymentSigner: k=v joined by &, keys sorted, hex HMAC-SHA256. */
function signPayment(payNo, channelTxnNo, amount) {
  const canonical = `amount=${amount}&channelTxnNo=${channelTxnNo}&payNo=${payNo}`;
  return hmac('sha256', paySecret, canonical, 'hex');
}

export default function () {
  const [token, userId] = users[(offset + exec.scenario.iterationInTest) % users.length];
  const headers = { authorization: token };
  if (seckillSecret) {
    const expiry = Math.floor(Date.now() / 1000) + tokenTtl;
    headers['X-Seckill-Token'] =
      `${expiry}.${hmac('sha256', seckillSecret, `${userId}:${__ENV.VOUCHER_ID}:${expiry}`, 'base64rawurl')}`;
  }
  const res = http.post(`${__ENV.BASE_URL}/voucher-order/seckill/${__ENV.VOUCHER_ID}`, null, {
    headers,
    timeout: __ENV.TIMEOUT || '10s',
  });
  const outcome = classify(res);
  counters[outcome].add(1);
  if (outcome !== 'accepted') {
    return;
  }

  const orderNo = res.json().data;
  // The trace nginx minted for this request, echoed back by the application. The order row will
  // carry the same one, which is how the scenario checks the chain end to end.
  const trace = res.headers['X-Trace-Id'] || '';
  console.log(`ACCEPTED order=${orderNo} user=${userId} trace=${trace}`);

  // The order is admitted, not yet persisted: a consumer batch has to land first.
  const startedWaiting = Date.now();
  let persisted = false;
  for (let attempt = 0; attempt < pollAttempts && !persisted; attempt++) {
    sleep(pollInterval);
    const status = http.get(`${__ENV.BASE_URL}/voucher-order/status/${orderNo}`, { headers });
    if (status.status === 200) {
      try {
        const body = status.json();
        persisted = body.success && body.data && body.data.orderStatus;
      } catch (e) {
        // keep polling
      }
    }
  }
  if (!persisted) {
    phases.never_persisted.add(1);
    return;
  }
  persistWait.add((Date.now() - startedWaiting) / 1000);

  // The buyers who walk away: their stock comes back through the timeout close.
  if (Math.random() >= payRatio) {
    phases.left_unpaid.add(1);
    return;
  }

  const prepay = http.post(`${__ENV.BASE_URL}/orders/${orderNo}/pay`, null, { headers });
  let payNo;
  let amount;
  try {
    const body = prepay.json();
    if (!body.success) {
      phases.pay_rejected.add(1);
      return;
    }
    payNo = body.data.payNo;
    amount = body.data.amount;
  } catch (e) {
    phases.pay_rejected.add(1);
    return;
  }

  const channelTxnNo = `MOCKBENCH${payNo}`;
  const callback = http.post(`${__ENV.BASE_URL}/payment/callback`,
    JSON.stringify({ payNo, channelTxnNo, amount, sign: signPayment(payNo, channelTxnNo, amount) }),
    { headers: { 'Content-Type': 'application/json' } });
  if (callback.status === 200) {
    phases.paid.add(1);
  } else {
    phases.callback_failed.add(1);
  }
}

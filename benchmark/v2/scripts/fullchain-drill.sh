#!/usr/bin/env bash
# The M8 full-chain scenario: 100k buyers, 1000 units, three instances behind nginx.
#
#   benchmark/v2/scripts/fullchain-drill.sh [STOCK] [RATE] [USERS]
#
# Wave 1  every buyer tries once. The winners wait for their order to reach MySQL; PAY_RATIO of
#         them pay through the API and the signed callback, the rest walk away.
# Close   the unpaid orders reach their deadline. The timer message closes them and their unit
#         goes back to the Redis bucket it came from and to the MySQL stock row.
# Wave 2  fresh buyers take exactly the units that came back, which is the part that proves the
#         return path is real and not just a status change.
# Check   live orders = stock on sale, no oversell, no buyer twice, Redis stock 0, DB stock 0,
#         no reservation left behind, and one order's trace found in the log of whichever
#         instance persisted it.
#
# Needs a cluster stack with APP_INSTANCES applications and nginx already running (see
# scripts/stack.sh lb-start). Prints one CSV line.
set -euo pipefail
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
STOCK="${1:-1000}" RATE="${2:-20000}" USERS="${3:-100000}"
# Wave 2 buys with users wave 1 never touched, so a winner meeting their own live order can
# never be mistaken for the returned stock running out.
WAVE2_USERS="${WAVE2_USERS:-$(( USERS / 4 ))}"
WAVE2_RATE="${WAVE2_RATE:-$RATE}"
STACK_ID="${STACK_ID:-v2}"
RUN_DIR="${PROJECT_DIR}/benchmark/v2/run/${STACK_ID}"
OUT="${BENCH_OUT:-${PROJECT_DIR}/benchmark/v2/${MILESTONE:-m8}}/raw"
FIXTURE="${PROJECT_DIR}/benchmark/v2/scripts/fixture.py"
BUCKETS="${SECKILL_BUCKETS:?set SECKILL_BUCKETS to the bucket count of the application under test}"
APP_INSTANCES="${APP_INSTANCES:-1}"
MANAGEMENT_PORT="${MANAGEMENT_PORT:-28184}"
PAY_RATIO="${PAY_RATIO:-0.7}"
PAY_SECRET="${PAY_SECRET:?set PAY_SECRET to the local-deals.payment.callback-secret of the application}"
PAY_TIMEOUT_S="${PAY_TIMEOUT_S:-60}"
# After the deadline: the timer message, then the fallback scan's grace, then MySQL.
CLOSE_WAIT_S="${CLOSE_WAIT_S:-$(( PAY_TIMEOUT_S + 90 ))}"
DRILL_TIMEOUT="${DRILL_TIMEOUT:-1800}"
eval "$("${PROJECT_DIR}/scripts/stack.sh" env)"
mkdir -p "$OUT"
LB="${STACK_LB:?nginx is not configured}"

redis() { $STACK_REDIS "$@"; }
sum_over_buckets() { # command, key suffix after the bucket prefix
  local command="$1" suffix="$2" bucket total=0 value
  for (( bucket = 0; bucket < BUCKETS; bucket++ )); do
    value="$(redis "$command" "sk:{sk:b${bucket}}:${suffix}" 2>/dev/null || echo 0)"
    [[ "$value" =~ ^-?[0-9]+$ ]] || value=0
    total=$(( total + value ))
  done
  echo "$total"
}
reservations_of() { sum_over_buckets HLEN "resv:$1"; }
redis_stock_of() { sum_over_buckets GET "stock:$1"; }
processing_left_total() { sum_over_buckets ZCARD "processing"; }
db_stock_of() { $STACK_MYSQL -N -B -e "SELECT stock FROM tb_seckill_voucher WHERE voucher_id = $1" 2>/dev/null; }
orders_of() { python3 "$FIXTURE" orders "$1"; }
stats_of() { python3 "$FIXTURE" order-stats "$1"; }

k6_metric() { # file metric
  python3 - "$1" "$2" <<'PY'
import json, sys
try:
    print(int(json.load(open(sys.argv[1]))['metrics'].get(sys.argv[2], {}).get('count', 0)))
except Exception:
    print(0)
PY
}

run_k6() { # script name rate duration extra-env...
  local script="$1" name="$2" rate="$3" duration="$4"; shift 4
  docker run --rm --network host --cpuset-cpus "${K6_CPUS:-6-7,14-15}" --user "$(id -u):$(id -g)" \
    -v "${PROJECT_DIR}/benchmark/v2/scripts:/scripts:ro" -v "${PROJECT_DIR}/benchmark/v2/run:/data:ro" \
    -v "${OUT}:/out" "${K6_IMAGE:-grafana/k6:2.2.0}" run --quiet \
    -e BASE_URL="$LB" -e VOUCHER_ID="$voucher" -e RATE="$rate" -e DURATION="$duration" \
    -e TOKENS=/data/tokens.csv "$@" \
    ${SECKILL_TOKEN_SECRET:+-e SECKILL_TOKEN_SECRET="$SECKILL_TOKEN_SECRET"} \
    --summary-export "/out/${name}.json" "/scripts/${script}" >"${OUT}/${name}.log" 2>&1 || true
}

# Sum one counter across every instance's own actuator, and record the split: three instances
# that all admitted buyers is the evidence that the load really was spread.
instance_counter() { # metric-with-labels -> "total per_instance"
  local metric="$1" n value total=0 split=""
  for (( n = 1; n <= APP_INSTANCES; n++ )); do
    value="$(curl -fsS --max-time 10 "http://127.0.0.1:$(( MANAGEMENT_PORT + n - 1 ))/actuator/prometheus" 2>/dev/null |
      awk -v m="$metric" 'index($0, m) == 1 { print $2; exit }')"
    [[ -n "$value" ]] || value=0
    value="${value%%.*}"
    total=$(( total + value ))
    split="${split}${split:+|}${value}"
  done
  echo "${total} ${split}"
}

wait_until_orders_stop() { # voucher expected -> seconds waited
  local voucher="$1" expected="$2" started previous=-1 stable=0 count
  started="$(date +%s.%N)"
  while :; do
    count="$(orders_of "$voucher")"
    if (( count >= expected )) && (( count == previous )); then
      stable=$(( stable + 1 ))
      (( stable < 3 )) || break
    else
      stable=0
    fi
    previous="$count"
    if python3 -c "import sys; sys.exit(0 if $(date +%s.%N) - ${started} > ${DRILL_TIMEOUT} else 1)"; then
      echo "timeout waiting for orders (${count}/${expected})" >&2
      break
    fi
    sleep 2
  done
  python3 -c "print(round($(date +%s.%N) - ${started}, 1))"
}

voucher="$(SECKILL_BUCKETS="$BUCKETS" python3 "$FIXTURE" voucher "$STOCK")"
stamp="$(date +%H%M%S)"

# ---- wave 1: everyone tries, some pay ------------------------------------------------------
accepted_before="$(instance_counter 'local_deals_seckill_requests_total{result="accepted",}' | cut -d' ' -f1)"
wave1="fullchain-wave1-v${voucher}-${stamp}"
run_k6 fullchain.js "$wave1" "$RATE" "$(( USERS / RATE + 1 ))s" \
  -e USER_OFFSET=0 -e PAY_RATIO="$PAY_RATIO" -e PAY_SECRET="$PAY_SECRET" \
  -e POLL_ATTEMPTS=60 -e GRACEFUL_STOP=180s
accepted1="$(k6_metric "${OUT}/${wave1}.json" outcome_accepted)"
paid1="$(k6_metric "${OUT}/${wave1}.json" phase_paid)"
unpaid1="$(k6_metric "${OUT}/${wave1}.json" phase_left_unpaid)"
never_persisted="$(k6_metric "${OUT}/${wave1}.json" phase_never_persisted)"
drain1="$(wait_until_orders_stop "$voucher" "$accepted1")"
stats1="$(stats_of "$voucher")"

# One order, and the instance whose log holds the request that created it. This is the whole
# point of the trace: the row is in one of eight tables, the log line is on one of three hosts,
# and nothing else connects them.
trace_row="$(python3 "$FIXTURE" one-trace "$voucher")"
trace_id="${trace_row##*,}"
trace_found="none"
if [[ -n "$trace_id" && "$trace_row" == *,* ]]; then
  for log in "${RUN_DIR}"/app.log "${RUN_DIR}"/app-i*.log; do
    [[ -f "$log" ]] || continue
    if grep -q -- "$trace_id" "$log"; then
      trace_found="$(basename "$log")"
      break
    fi
  done
  # nginx saw it first; its access log says which upstream served that request.
  if [[ -f "${RUN_DIR}/access.log" ]]; then
    grep -m1 -- "$trace_id" "${RUN_DIR}/access.log" >"${OUT}/${wave1}-trace.txt" 2>/dev/null || true
  fi
  echo "order=${trace_row%,*} trace=${trace_id} app_log=${trace_found}" >>"${OUT}/${wave1}-trace.txt"
fi

# ---- the unpaid orders time out and give their units back ----------------------------------
echo "waiting up to ${CLOSE_WAIT_S}s for ${unpaid1} unpaid orders to close" >&2
closed_deadline=$(( SECONDS + CLOSE_WAIT_S ))
while (( unpaid1 > 0 && SECONDS < closed_deadline )); do
  (( $(redis_stock_of "$voucher") < unpaid1 )) || break
  sleep 5
done
redis_stock_after_close="$(redis_stock_of "$voucher")"
db_stock_after_close="$(db_stock_of "$voucher")"

# ---- wave 2: the returned units are taken again ---------------------------------------------
wave2="fullchain-wave2-v${voucher}-${stamp}"
run_k6 seckill.js "$wave2" "$WAVE2_RATE" "$(( WAVE2_USERS / WAVE2_RATE + 1 ))s" \
  -e USER_OFFSET="$USERS"
accepted2="$(k6_metric "${OUT}/${wave2}.json" outcome_accepted)"
drain2="$(wait_until_orders_stop "$voucher" "$(( accepted1 + accepted2 ))")"

# ---- the reconciliation ---------------------------------------------------------------------
total=0 live=0 closed=0 paid=0 live_buyers=0
final="$(stats_of "$voucher")"
# fixture.py order-stats prints exactly these five key=value pairs and nothing else.
eval "${final//,/ }"
redis_stock="$(redis_stock_of "$voucher")"
db_stock="$(db_stock_of "$voucher")"
reservations="$(reservations_of "$voucher")"
processing_left="$(processing_left_total)"
read -r accepted_total accepted_split <<<"$(instance_counter 'local_deals_seckill_requests_total{result="accepted",}')"
read -r persisted_total persisted_split <<<"$(instance_counter 'local_deals_seckill_consume_batch_size_orders_count{stage="persisted",}')"
oversold=$(( live > STOCK ? live - STOCK : 0 ))
duplicate_buyers=$(( live - live_buyers ))

echo "voucher=${voucher},stock=${STOCK},users=${USERS},rate=${RATE},pay_ratio=${PAY_RATIO}" \
     ",instances=${APP_INSTANCES},accepted_wave1=${accepted1},paid_wave1=${paid1}" \
     ",left_unpaid_wave1=${unpaid1},never_persisted=${never_persisted},drain1_s=${drain1}" \
     ",returned_to_redis=${redis_stock_after_close},db_stock_after_close=${db_stock_after_close}" \
     ",accepted_wave2=${accepted2},drain2_s=${drain2},orders_total=${total},orders_live=${live}" \
     ",orders_closed=${closed},orders_paid=${paid},live_buyers=${live_buyers}" \
     ",duplicate_buyers=${duplicate_buyers},oversold=${oversold},redis_stock=${redis_stock}" \
     ",db_stock=${db_stock},reservations=${reservations},processing_left=${processing_left}" \
     ",admitted_by_instance=${accepted_split},persisted_by_instance=${persisted_split}" \
     ",trace_id=${trace_id:-none},trace_in_log=${trace_found}" | tr -d ' '

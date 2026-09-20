#!/usr/bin/env bash
# kill -9 the application while winners are being admitted, restart it, and time how long it
# takes until every Redis reservation of the voucher is an order in MySQL again.
#
#   benchmark/v2/scripts/kill-drill.sh [STOCK] [RATE] [KILL_AFTER_S]
#
# BROKER_DOWN=1 kills the broker two seconds before the application: sends fail after the
# admission Lua succeeded, so the kill leaves reservations whose message was never stored (the
# case the reconciler must redrive). The broker is started again together with the app.
#
# Needs a running stack and app (scripts/stack.sh up / app-start) and the bench token fixture.
# Prints one CSV line: killed_at_accepted, reservations, orders, redis_stock, db_stock,
# processing_left, converge_s. The restarted app keeps APP_ARGS/APP_JAR/APP_CPUS from the env.
set -euo pipefail
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
STOCK="${1:-20000}" RATE="${2:-2000}" KILL_AFTER="${3:-5}"
STACK_ID="${STACK_ID:-v2}"
RUN_DIR="${PROJECT_DIR}/benchmark/v2/run/${STACK_ID}"
OUT="${BENCH_OUT:-${PROJECT_DIR}/benchmark/v2/${MILESTONE:-m3}}/raw"
FIXTURE="${PROJECT_DIR}/benchmark/v2/scripts/fixture.py"
eval "$("${PROJECT_DIR}/scripts/stack.sh" env)"
mkdir -p "$OUT"
redis() { $STACK_REDIS "$@"; }

# Since M5 a voucher's Redis state is spread over SECKILL_BUCKETS buckets; unset or 0 reads the
# single pre-M5 keys. Every total below is the sum over the buckets.
BUCKETS="${SECKILL_BUCKETS:-0}"
sum_over_buckets() { # command, key suffix after the bucket prefix
  local command="$1" suffix="$2" bucket total=0 value
  for (( bucket = 0; bucket < BUCKETS; bucket++ )); do
    value="$(redis "$command" "sk:{sk:b${bucket}}:${suffix}" 2>/dev/null || echo 0)"
    [[ "$value" =~ ^-?[0-9]+$ ]] || value=0
    total=$(( total + value ))
  done
  echo "$total"
}
reservations_of() { # voucher
  if (( BUCKETS > 0 )); then sum_over_buckets HLEN "resv:$1"; else redis HLEN "seckill:reservation:$1"; fi
}
processing_left_total() {
  if (( BUCKETS > 0 )); then sum_over_buckets ZCARD "processing"; else redis ZCARD seckill:order:processing; fi
}
redis_stock_of() { # voucher
  if (( BUCKETS > 0 )); then sum_over_buckets GET "stock:$1"; else redis GET "seckill:stock:$1"; fi
}

voucher="$(python3 "$FIXTURE" voucher "$STOCK")"
name="kill-drill-v${voucher}-$(date +%H%M%S)"
docker run --rm --network host --cpuset-cpus "${K6_CPUS:-6-7,14-15}" --user "$(id -u):$(id -g)" \
  -v "${PROJECT_DIR}/benchmark/v2/scripts:/scripts:ro" -v "${PROJECT_DIR}/benchmark/v2/run:/data:ro" \
  -v "${OUT}:/out" "${K6_IMAGE:-grafana/k6:2.2.0}" run --quiet \
  -e BASE_URL="$STACK_APP" -e VOUCHER_ID="$voucher" -e RATE="$RATE" -e DURATION="$(( KILL_AFTER * 2 ))s" \
  -e TOKENS=/data/tokens.csv -e USER_OFFSET=0 -e TIMEOUT=2s ${SECKILL_TOKEN_SECRET:+-e SECKILL_TOKEN_SECRET="$SECKILL_TOKEN_SECRET"} \
  --summary-export "/out/${name}.json" /scripts/seckill.js >"${OUT}/${name}.log" 2>&1 &
k6=$!
if [[ -n "${BROKER_DOWN:-}" ]]; then
  sleep "$(( KILL_AFTER - 2 ))"; docker kill "ld-${STACK_ID}-broker" >/dev/null; sleep 2
else
  sleep "$KILL_AFTER"
fi
kill -9 "$(<"${RUN_DIR}/app.pid")"
killed="$(date +%s.%N)"
at_kill="$(reservations_of "$voucher")"
if [[ -n "${BROKER_DOWN:-}" ]]; then
  docker start "ld-${STACK_ID}-broker" >/dev/null
  until docker exec "ld-${STACK_ID}-broker" sh mqadmin clusterList -n namesrv:9876 2>/dev/null | grep -q "ld-${STACK_ID}"; do sleep 2; done
fi
wait "$k6" || true
"${PROJECT_DIR}/scripts/stack.sh" app-start >/dev/null
while :; do
  resv="$(reservations_of "$voucher")"
  orders="$(python3 "$FIXTURE" orders "$voucher")"
  left="$(processing_left_total)"
  if (( resv == orders && left == 0 )); then break; fi
  if python3 -c "import sys; sys.exit(0 if $(date +%s.%N) - ${killed} > ${DRILL_TIMEOUT:-1200} else 1)"; then
    echo "timeout: reservations=${resv} orders=${orders} processing=${left}" >&2; break
  fi
  sleep 2
done
converge="$(python3 -c "print(round($(date +%s.%N) - ${killed}, 1))")"
redis_stock="$(redis_stock_of "$voucher")"
db_stock="$($STACK_MYSQL -N -B -e "SELECT stock FROM tb_seckill_voucher WHERE voucher_id = ${voucher}" 2>/dev/null)"
redriven="$(grep -c 'published again' "${RUN_DIR}/app.log" || true)"
echo "voucher=${voucher},redriven=${redriven},reserved_at_kill=${at_kill},reservations=${resv},orders=${orders},redis_stock=${redis_stock},db_stock=${db_stock},processing_left=${left},converge_s=${converge}"

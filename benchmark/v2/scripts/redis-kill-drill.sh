#!/usr/bin/env bash
# Kill one Redis Cluster master while winners are being admitted and measure what it costs.
#
#   benchmark/v2/scripts/redis-kill-drill.sh [STOCK] [RATE] [KILL_AFTER_S]
#
# Redis replication is asynchronous, so a master that dies takes its unreplicated writes with
# it: reservations and stock decrements that the buyer was already told about. The promoted
# replica therefore comes back with *more* stock than the truth. This drill quantifies that
# (the RPO) and checks the claim M5 rests on: the Redis layer may lose writes, and MySQL's
# conditional `stock = stock - n WHERE stock >= n` still keeps the number of orders at or below
# the stock that was on sale — no oversell.
#
# Needs a running cluster stack (REDIS_MODE=cluster scripts/stack.sh up), the app started
# against it, and the bench token fixture. SECKILL_BUCKETS must match the app's bucket count.
#
# Prints one CSV line: voucher, stock, reserved_at_kill, killed_node, failover_s, reservations,
# orders, redis_stock, db_stock, processing_left, lost_reservations, oversold, converge_s.
set -euo pipefail
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
STOCK="${1:-20000}" RATE="${2:-2000}" KILL_AFTER="${3:-6}"
STACK_ID="${STACK_ID:-v2}"
RUN_DIR="${PROJECT_DIR}/benchmark/v2/run/${STACK_ID}"
OUT="${BENCH_OUT:-${PROJECT_DIR}/benchmark/v2/${MILESTONE:-m5}}/raw"
FIXTURE="${PROJECT_DIR}/benchmark/v2/scripts/fixture.py"
# The node to kill; it owns the first third of the slots, so most buckets live on it.
KILL_NODE="${REDIS_KILL_NODE:-ld-${STACK_ID}-redis-c1}"
BUCKETS="${SECKILL_BUCKETS:?set SECKILL_BUCKETS to the bucket count of the application under test}"
eval "$("${PROJECT_DIR}/scripts/stack.sh" env)"
mkdir -p "$OUT"
# Every read of this drill must go through a node that survives it: STACK_REDIS points at the
# first node, which is the one being killed.
SURVIVOR="${REDIS_SURVIVOR:-$(cut -d, -f2 <<<"${STACK_REDIS_CLUSTER_NODES:?cluster mode is required}")}"
redis() {
  redis-cli -c -h "${SURVIVOR%%:*}" -p "${SURVIVOR##*:}" -a "$LOCAL_DEALS_REDIS_PASSWORD" \
    --no-auth-warning "$@"
}

# Every total is the sum over the buckets; a bucket key is prefixed with its hash tag.
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
cluster_ok() { redis CLUSTER INFO 2>/dev/null | grep -q '^cluster_state:ok'; }
cluster_noticed() { # the survivors have flagged the dead master
  redis CLUSTER NODES 2>/dev/null | grep -q 'fail'
}
# Every bucket key is readable again: this is the moment the application stops seeing errors,
# and it is the number worth quoting, not "the cluster says ok" (the survivors still say ok in
# the first seconds, before they have noticed anything at all).
buckets_readable() {
  local bucket
  for (( bucket = 0; bucket < BUCKETS; bucket++ )); do
    redis GET "sk:{sk:b${bucket}}:stock:$1" >/dev/null 2>&1 || return 1
  done
  return 0
}
accepted_by_k6() {
  python3 - "${OUT}/${name}.json" <<'PYJSON'
import json, sys
try:
    metrics = json.load(open(sys.argv[1]))['metrics']
except Exception:
    print(0); raise SystemExit
print(int(metrics.get('outcome_accepted', {}).get('count', 0)))
PYJSON
}

voucher="$(SECKILL_BUCKETS="$BUCKETS" python3 "$FIXTURE" voucher "$STOCK")"
name="redis-kill-drill-v${voucher}-$(date +%H%M%S)"
docker run --rm --network host --cpuset-cpus "${K6_CPUS:-6-7,14-15}" --user "$(id -u):$(id -g)" \
  -v "${PROJECT_DIR}/benchmark/v2/scripts:/scripts:ro" -v "${PROJECT_DIR}/benchmark/v2/run:/data:ro" \
  -v "${OUT}:/out" "${K6_IMAGE:-grafana/k6:2.2.0}" run --quiet \
  -e BASE_URL="$STACK_APP" -e VOUCHER_ID="$voucher" -e RATE="$RATE" -e DURATION="$(( STOCK / RATE + 5 ))s" \
  -e TOKENS=/data/tokens.csv -e USER_OFFSET=0 -e TIMEOUT=2s ${SECKILL_TOKEN_SECRET:+-e SECKILL_TOKEN_SECRET="$SECKILL_TOKEN_SECRET"} \
  --summary-export "/out/${name}.json" /scripts/seckill.js >"${OUT}/${name}.log" 2>&1 &
k6=$!

sleep "$KILL_AFTER"
reserved_at_kill="$(reservations_of "$voucher")"
stock_at_kill="$(redis_stock_of "$voucher")"
docker kill "$KILL_NODE" >/dev/null
killed="$(date +%s.%N)"
# Two different moments: when the survivors notice the master is gone, and when every bucket is
# served again. The application feels the second one.
noticed=""
recovered=""
while [[ -z "$recovered" ]]; do
  [[ -n "$noticed" ]] || ! cluster_noticed || noticed="$(python3 -c "print(round($(date +%s.%N) - ${killed}, 1))")"
  if [[ -n "$noticed" ]] && cluster_ok && buckets_readable "$voucher"; then
    recovered="$(python3 -c "print(round($(date +%s.%N) - ${killed}, 1))")"
    break
  fi
  if python3 -c "import sys; sys.exit(0 if $(date +%s.%N) - ${killed} > ${FAILOVER_TIMEOUT:-120} else 1)"; then
    echo "timeout waiting for failover" >&2
    noticed="${noticed:-timeout}"; recovered=timeout
    break
  fi
  sleep 0.5
done
wait "$k6" || true

# Converge: no reservation is still being processed and the order count has stopped moving.
# Reservations lost with the dead master can never become orders, so "reservations == orders"
# is not the condition here; it is one of the numbers being reported.
stable=0
previous=-1
while :; do
  orders="$(python3 "$FIXTURE" orders "$voucher")"
  left="$(processing_left_total)"
  if (( left == 0 && orders == previous )); then
    stable=$(( stable + 1 ))
    (( stable < 5 )) || break
  else
    stable=0
  fi
  previous="$orders"
  if python3 -c "import sys; sys.exit(0 if $(date +%s.%N) - ${killed} > ${DRILL_TIMEOUT:-1200} else 1)"; then
    echo "timeout: orders=${orders} processing=${left}" >&2; break
  fi
  sleep 2
done
converge="$(python3 -c "print(round($(date +%s.%N) - ${killed}, 1))")"

resv="$(reservations_of "$voucher")"
redis_stock="$(redis_stock_of "$voucher")"
db_stock="$($STACK_MYSQL -N -B -e "SELECT stock FROM tb_seckill_voucher WHERE voucher_id = ${voucher}" 2>/dev/null)"
accepted="$(accepted_by_k6)"
# What the buyers lost: everyone k6 was told had won, minus the orders that exist. A reservation
# that died with its master can never become an order.
lost="$(( accepted - orders ))"
(( lost >= 0 )) || lost=0
# What Redis believes it still has, minus the truth: the stock that came back from the dead.
stock_gap="$(( redis_stock - db_stock ))"
oversold="$(( orders > STOCK ? orders - STOCK : 0 ))"
docker start "$KILL_NODE" >/dev/null || true
echo "voucher=${voucher},stock=${STOCK},killed_node=${KILL_NODE},reserved_at_kill=${reserved_at_kill},stock_at_kill=${stock_at_kill},noticed_s=${noticed},recovered_s=${recovered},accepted=${accepted},orders=${orders},reservations=${resv},redis_stock=${redis_stock},db_stock=${db_stock},processing_left=${left},lost_admissions=${lost},stock_gap=${stock_gap},oversold=${oversold},converge_s=${converge}"

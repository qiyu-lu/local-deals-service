#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

fail() {
  echo "M5A fault gate failed: $*" >&2
  exit 1
}

require_env() {
  local name="$1"
  [[ -n "${!name:-}" ]] || fail "${name} must be set explicitly"
}

[[ "${M5A_ISOLATED:-}" == "true" ]] || fail "set M5A_ISOLATED=true"
for name in M5A_RUN_ID M5A_APP_HOST M5A_APP_PORT M5A_MANAGEMENT_PORT \
  M5A_MYSQL_PORT M5A_MYSQL_SCHEMA M5A_MYSQL_USER M5A_MYSQL_PASSWORD \
  M5A_REDIS_PORT M5A_REDIS_PASSWORD M5A_ES_PORT M5A_RMQ_NAMESRV_PORT \
  M5A_RMQ_BROKER_PORT; do
  require_env "$name"
done
[[ "$M5A_RUN_ID" =~ ^[a-z0-9][a-z0-9-]{5,30}$ ]] || fail "invalid M5A_RUN_ID"
[[ "$M5A_MYSQL_SCHEMA" == "m5a_${M5A_RUN_ID//-/_}" ]] || fail "schema/run-id mismatch"
[[ "$M5A_APP_HOST" == "127.0.0.1" ]] || fail "fault runner only accepts loopback app"

export M5A_MYSQL_PASSWORD M5A_REDIS_PASSWORD
"${PROJECT_DIR}/scripts/m5a-isolated-stack.sh" status >/dev/null

APP_URL="http://${M5A_APP_HOST}:${M5A_APP_PORT}"
MANAGEMENT_URL="http://127.0.0.1:${M5A_MANAGEMENT_PORT}"
ARTIFACT_DIR="${PROJECT_DIR}/benchmark/m5a/${M5A_RUN_ID}/faults"
mkdir -p "$ARTIFACT_DIR"
SUMMARY="$ARTIFACT_DIR/fault-matrix.csv"
printf '%s\n' 'run_id,scenario,phase,requests,http_2xx,http_4xx,http_5xx,transport_errors,collector_value,recovery_seconds,invariant_result,notes' > "$SUMMARY"

PRE_SECONDS="${M5A_FAULT_PRE_SECONDS:-30}"
FAULT_SECONDS="${M5A_FAULT_SECONDS:-60}"
RECOVERY_LIMIT="${M5A_RECOVERY_LIMIT_SECONDS:-300}"
SCENARIOS="${M5A_FAULT_SCENARIOS:-F1,F2,F3,F4}"

mysql_cli() {
  MYSQL_PWD="$M5A_MYSQL_PASSWORD" mysql --protocol=tcp -h 127.0.0.1 \
    -P "$M5A_MYSQL_PORT" -u "$M5A_MYSQL_USER" --batch --skip-column-names \
    "$M5A_MYSQL_SCHEMA" "$@"
}

assert_owned_container() {
  local name="$1" label
  label="$(docker inspect --format '{{ index .Config.Labels "com.localdeals.m5a.run-id" }}' "$name" 2>/dev/null || true)"
  [[ "$label" == "$M5A_RUN_ID" ]] || fail "refusing to touch non-owned container: $name"
}

restore_dependencies() {
  local suffix name
  for suffix in mysql redis broker es; do
    name="m5a-${M5A_RUN_ID}-${suffix}"
    if docker inspect "$name" >/dev/null 2>&1; then
      local running
      running="$(docker inspect --format '{{.State.Running}}' "$name")"
      if [[ "$running" != "true" ]]; then
        assert_owned_container "$name"
        docker start "$name" >/dev/null || true
      fi
    fi
  done
}
trap restore_dependencies EXIT

prom_value() {
  local metric="$1"
  curl --silent --max-time 2 "${MANAGEMENT_URL}/actuator/prometheus" |
    awk -v metric="$metric" '
      $0 !~ /^#/ && ($1 == metric || index($1, metric "{") == 1) {
        sum += $NF
        found=1
      }
      END {if(found) print sum+0; else print ""}
    '
}

traffic_window() {
  local scenario="$1" phase="$2" seconds="$3" path="$4" token="${5:-}"
  local deadline=$(( $(date +%s) + seconds ))
  local requests=0 ok=0 client_error=0 server_error=0 transport=0 code
  while (( $(date +%s) < deadline )); do
    if [[ -n "$token" ]]; then
      code="$(curl --silent --max-time 2 --output /dev/null --write-out '%{http_code}' \
        -H "authorization: $token" "${APP_URL}${path}" || true)"
    else
      code="$(curl --silent --max-time 2 --output /dev/null --write-out '%{http_code}' \
        "${APP_URL}${path}" || true)"
    fi
    requests=$((requests + 1))
    case "$code" in
      2??) ok=$((ok + 1)) ;;
      4??) client_error=$((client_error + 1)) ;;
      5??) server_error=$((server_error + 1)) ;;
      *) transport=$((transport + 1)) ;;
    esac
    sleep 1
  done
  printf '%s,%s,%s,%s,%s,%s,%s,%s' \
    "$M5A_RUN_ID" "$scenario" "$phase" "$requests" "$ok" "$client_error" "$server_error" "$transport"
}

invariants() {
  local duplicate negative_stock like_mismatch
  duplicate="$(mysql_cli -e 'SELECT COUNT(*) FROM (SELECT user_id,voucher_id FROM tb_voucher_order GROUP BY user_id,voucher_id HAVING COUNT(*)>1) d' 2>/dev/null || echo unavailable)"
  negative_stock="$(mysql_cli -e 'SELECT COUNT(*) FROM tb_seckill_voucher WHERE stock < 0' 2>/dev/null || echo unavailable)"
  like_mismatch="$(mysql_cli -e 'SELECT COUNT(*) FROM tb_blog b WHERE b.liked <> b.legacy_liked_offset + (SELECT COUNT(*) FROM tb_blog_like l WHERE l.blog_id=b.id)' 2>/dev/null || echo unavailable)"
  if [[ "$duplicate" == "0" && "$negative_stock" == "0" && "$like_mismatch" == "0" ]]; then
    echo pass
  elif [[ "$duplicate" == "unavailable" || "$negative_stock" == "unavailable" || "$like_mismatch" == "unavailable" ]]; then
    echo unavailable
  else
    echo "fail(duplicate=${duplicate};negative_stock=${negative_stock};like_mismatch=${like_mismatch})"
  fi
}

wait_dependency() {
  local suffix="$1" started="$(date +%s)" name="m5a-${M5A_RUN_ID}-${suffix}"
  while (( $(date +%s) - started < RECOVERY_LIMIT )); do
    case "$suffix" in
      redis)
        if redis-cli -h 127.0.0.1 -p "$M5A_REDIS_PORT" -a "$M5A_REDIS_PASSWORD" \
          --no-auth-warning PING 2>/dev/null | grep -qx PONG; then break; fi ;;
      mysql)
        if mysql_cli -e 'SELECT 1' >/dev/null 2>&1; then break; fi ;;
      es)
        if curl --fail --silent "http://127.0.0.1:${M5A_ES_PORT}/_cluster/health" >/dev/null; then break; fi ;;
      broker)
        if docker exec "$name" sh mqadmin clusterList -n "m5a-${M5A_RUN_ID}-namesrv:9876" \
          2>/dev/null | grep -q "m5a-${M5A_RUN_ID}"; then break; fi ;;
    esac
    sleep 2
  done
  echo "$(( $(date +%s) - started ))"
}

container_fault() {
  local scenario="$1" suffix="$2" path="$3" metric="$4" notes="$5" token="${6:-}"
  local name="m5a-${M5A_RUN_ID}-${suffix}" pre fault recovery_seconds recovered invariant
  assert_owned_container "$name"
  pre="$(traffic_window "$scenario" normal "$PRE_SECONDS" "$path" "$token")"
  printf '%s,%s,,%s,%s\n' "$pre" "$(prom_value "$metric")" "$(invariants)" "normal-waterline" >> "$SUMMARY"

  docker stop --time 10 "$name" >/dev/null
  fault="$(traffic_window "$scenario" fault "$FAULT_SECONDS" "$path" "$token")"
  printf '%s,%s,,%s,%s\n' "$fault" "$(prom_value "$metric")" "$(invariants)" "${notes//,/;}" >> "$SUMMARY"

  docker start "$name" >/dev/null
  recovery_seconds="$(wait_dependency "$suffix")"
  recovered="$(traffic_window "$scenario" recovered 5 "$path" "$token")"
  invariant="$(invariants)"
  printf '%s,%s,%s,%s,%s\n' "$recovered" "$(prom_value "$metric")" "$recovery_seconds" "$invariant" "dependency restarted; convergence bounded by ${RECOVERY_LIMIT}s" >> "$SUMMARY"
  [[ "$invariant" != fail* ]] || fail "P0 invariant failed in ${scenario}"
}

run_f3_consumer_pause() {
  local broker="m5a-${M5A_RUN_ID}-broker" output="$ARTIFACT_DIR/F3-consumer-pause.txt"
  assert_owned_container "$broker"
  if docker exec "$broker" sh mqadmin updateSubGroup \
      -n "m5a-${M5A_RUN_ID}-namesrv:9876" -c "m5a-${M5A_RUN_ID}" \
      -g seckill-consumer-group -s false > "$output" 2>&1; then
    sleep "$FAULT_SECONDS"
    docker exec "$broker" sh mqadmin updateSubGroup \
      -n "m5a-${M5A_RUN_ID}-namesrv:9876" -c "m5a-${M5A_RUN_ID}" \
      -g seckill-consumer-group -s true >> "$output" 2>&1 || true
    printf '%s,F3-consumer-pause,BLOCKED,0,0,0,0,0,%s,,blocked,"pause/resume control executed, but no accepted reservation was injected; run a bounded fixture before reporting pass"\n' \
      "$M5A_RUN_ID" "$(prom_value local_deals_seckill_processing_oldest_overdue_seconds)" >> "$SUMMARY"
  else
    printf '%s,F3-consumer-pause,BLOCKED,0,0,0,0,0,,,blocked,"broker does not support a verified pause command; not reported as pass"\n' \
      "$M5A_RUN_ID" >> "$SUMMARY"
  fi
}

[[ "$SCENARIOS" == *F1* ]] && container_fault F1-redis redis /shop/1 \
  local_deals_seckill_processing_due 'Redis unavailable; collector must become absent/NaN, not zero'
[[ "$SCENARIOS" == *F2* ]] && container_fault F2-mysql mysql /shop/999999999 \
  local_deals_blog_like_outbox_pending 'MySQL unavailable; cold miss and collector fail without invented zero'
if [[ "$SCENARIOS" == *F3* ]]; then
  run_f3_consumer_pause
  container_fault F3-broker broker /shop/1 local_deals_seckill_processing_oldest_overdue_seconds \
    'Broker unavailable; HTTP read control path remains independent'
fi
[[ "$SCENARIOS" == *F4* ]] && container_fault F4-elasticsearch es \
  '/shop/search?keyword=%E9%A4%90%E5%8E%85&current=1' local_deals_es_sync_messages_total \
  'Search current failure semantics recorded; no unbounded MySQL LIKE fallback added'

trap - EXIT
restore_dependencies
echo "M5A fault inventory complete: $SUMMARY"

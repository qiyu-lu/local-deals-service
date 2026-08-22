#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STACK_SCRIPT="${PROJECT_DIR}/scripts/m5d-isolated-stack.sh"
PROBE_SCRIPT="${PROJECT_DIR}/scripts/m5d-http-probe.py"
MAVEN_BIN="${MAVEN_BIN:-/home/sd101t/.m2/wrapper/dists/apache-maven-3.9.11/a2d47e15/bin/mvn}"
JAVA_HOME="${JAVA_HOME:-/home/sd101t/.jdks/dragonwell-ex-1.8.0_472}"
APP1_PID=""
APP2_PID=""
REDIS_RECOVERY="none"
MYSQL_STOPPED=false
ES_RECOVERY="none"
CONSUMER_PAUSED=false
BROKER_STOPPED=false
F4_LOCK_PID=""
F4_LOCK_CONNECTION=""

fail() {
  echo "M5D traffic check failed: $*" >&2
  exit 1
}

require_env() {
  local name="$1"
  [[ -n "${!name:-}" ]] || fail "${name} must be set explicitly"
}

for name in M5D_RUN_ID M5D_APP_HOST M5D_APP1_PORT M5D_MANAGEMENT1_PORT \
    M5D_APP2_PORT M5D_MANAGEMENT2_PORT M5D_MYSQL_PORT M5D_MYSQL_PASSWORD \
    M5D_REDIS_PORT M5D_REDIS_PASSWORD M5D_ES_PORT M5D_RMQ_NAMESRV_PORT \
    M5D_RMQ_BROKER_PORT M5D_RMQ_TOPIC M5D_RMQ_CONSUMER_GROUP \
    M5D_RMQ_PRODUCER_GROUP M5D_ES_SYNC_TOPIC M5D_ES_SYNC_CONSUMER_GROUP; do
  require_env "$name"
done
[[ "${M5D_ISOLATED:-}" == "true" ]] || fail "set M5D_ISOLATED=true"
[[ "$M5D_APP_HOST" == "127.0.0.1" ]] || fail "applications must bind to 127.0.0.1"
[[ -x "$MAVEN_BIN" && -x "$JAVA_HOME/bin/java" ]] || fail "Java 8/Maven toolchain missing"
for command in curl docker python3 ss; do
  command -v "$command" >/dev/null 2>&1 || fail "missing command: $command"
done

export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"
export M5D_REDIS_HOST=127.0.0.1
SCHEMA="m5d_${M5D_RUN_ID//-/_}"
ARTIFACT_DIR="${PROJECT_DIR}/benchmark/m5d/${M5D_RUN_ID}"
SUMMARY_CSV="${ARTIFACT_DIR}/scenario-summary.csv"
APP1_URL="http://${M5D_APP_HOST}:${M5D_APP1_PORT}"
APP2_URL="http://${M5D_APP_HOST}:${M5D_APP2_PORT}"
MGMT1_URL="http://${M5D_APP_HOST}:${M5D_MANAGEMENT1_PORT}"
MGMT2_URL="http://${M5D_APP_HOST}:${M5D_MANAGEMENT2_PORT}"
JAR="${PROJECT_DIR}/target/local-deals-service-0.0.1-SNAPSHOT.jar"

stack() {
  "$STACK_SCRIPT" "$@"
}

redis_cli() {
  docker exec "m5d-${M5D_RUN_ID}-redis" redis-cli \
    -a "$M5D_REDIS_PASSWORD" --no-auth-warning "$@"
}

mysql_exec() {
  docker exec "m5d-${M5D_RUN_ID}-mysql" mysql \
    -uroot "-p${M5D_MYSQL_PASSWORD}" "$SCHEMA" -N -s -e "$1"
}

cleanup() {
  set +e
  if [[ -n "$F4_LOCK_CONNECTION" ]]; then mysql_exec "KILL ${F4_LOCK_CONNECTION};" >/dev/null 2>&1; fi
  if [[ -n "$F4_LOCK_PID" ]]; then kill "$F4_LOCK_PID" >/dev/null 2>&1; wait "$F4_LOCK_PID" 2>/dev/null; fi
  if [[ "$BROKER_STOPPED" == true ]]; then stack broker-start; fi
  if [[ "$CONSUMER_PAUSED" == true ]]; then stack consumer-resume; fi
  if [[ "$ES_RECOVERY" == unpause ]]; then stack es-unpause; fi
  if [[ "$ES_RECOVERY" == start ]]; then stack es-start; fi
  if [[ "$MYSQL_STOPPED" == true ]]; then stack mysql-start; fi
  if [[ "$REDIS_RECOVERY" == unpause ]]; then stack redis-unpause; fi
  if [[ "$REDIS_RECOVERY" == start ]]; then stack redis-start; fi
  local pid
  for pid in "$APP1_PID" "$APP2_PID"; do
    if [[ -n "$pid" ]] && kill -0 "$pid" >/dev/null 2>&1; then
      kill "$pid"
      wait "$pid" 2>/dev/null
    fi
  done
}
trap cleanup EXIT INT TERM

wait_for_app() {
  local pid="$1" url="$2" log="$3" attempt
  for attempt in $(seq 1 150); do
    if curl --fail --silent --max-time 5 "${url}/actuator/health/readiness" >/dev/null 2>&1; then
      return 0
    fi
    if ! kill -0 "$pid" >/dev/null 2>&1; then
      tail -120 "$log" >&2 || true
      fail "application exited before readiness: $log"
    fi
    sleep 1
  done
  tail -120 "$log" >&2 || true
  fail "application readiness timed out: $log"
}

start_application() {
  local instance="$1" app_port="$2" management_port="$3"
  local log="${ARTIFACT_DIR}/application-${instance}.log"
  LOCAL_DEALS_DATASOURCE_URL="jdbc:mysql://127.0.0.1:${M5D_MYSQL_PORT}/${SCHEMA}?useSSL=false&serverTimezone=UTC" \
  LOCAL_DEALS_DATASOURCE_USERNAME=root \
  LOCAL_DEALS_DATASOURCE_PASSWORD="$M5D_MYSQL_PASSWORD" \
  LOCAL_DEALS_REDIS_HOST=127.0.0.1 \
  LOCAL_DEALS_REDIS_PORT="$M5D_REDIS_PORT" \
  LOCAL_DEALS_REDIS_PASSWORD="$M5D_REDIS_PASSWORD" \
  LOCAL_DEALS_REDIS_COMMAND_TIMEOUT=500ms \
  LOCAL_DEALS_REDIS_POOL_MAX_WAIT=500ms \
  LOCAL_DEALS_MANAGEMENT_PORT="$management_port" \
  LOCAL_DEALS_MANAGEMENT_ADDRESS="$M5D_APP_HOST" \
  SERVER_ADDRESS="$M5D_APP_HOST" \
  SERVER_PORT="$app_port" \
  SPRING_ELASTICSEARCH_REST_URIS="http://127.0.0.1:${M5D_ES_PORT}" \
  ROCKETMQ_NAME_SERVER="127.0.0.1:${M5D_RMQ_NAMESRV_PORT}" \
  LOCAL_DEALS_ROCKETMQ_PRODUCER_GROUP="$M5D_RMQ_PRODUCER_GROUP" \
  LOCAL_DEALS_SECKILL_TOPIC="$M5D_RMQ_TOPIC" \
  LOCAL_DEALS_SECKILL_CONSUMER_GROUP="$M5D_RMQ_CONSUMER_GROUP" \
  LOCAL_DEALS_ES_SYNC_TOPIC="$M5D_ES_SYNC_TOPIC" \
  LOCAL_DEALS_ES_SYNC_CONSUMER_GROUP="$M5D_ES_SYNC_CONSUMER_GROUP" \
  LOCAL_DEALS_OBSERVABILITY_SAMPLING_ENABLED=true \
  LOCAL_DEALS_OBSERVABILITY_INITIAL_DELAY=1s \
  LOCAL_DEALS_OBSERVABILITY_SAMPLING_INTERVAL=15s \
  LOCAL_DEALS_SECKILL_RECONCILIATION_STALE_AFTER=2s \
  LOCAL_DEALS_TRUSTED_PROXIES=127.0.0.1/32 \
  LOCAL_DEALS_SECKILL_RATE_WINDOW=10s \
  LOCAL_DEALS_SECKILL_ACTIVITY_LIMIT=300 \
  LOCAL_DEALS_SECKILL_USER_LIMIT=2 \
  LOCAL_DEALS_SECKILL_IP_LIMIT=100 \
  LOCAL_DEALS_DB_READ_MAX_CONCURRENT=4 \
  LOCAL_DEALS_DB_READ_MAX_WAIT=20ms \
  LOCAL_DEALS_SHARED_LOAD_WAIT=750ms \
  LOCAL_DEALS_SEARCH_MAX_CONCURRENT=4 \
  LOCAL_DEALS_SEARCH_MAX_WAIT=20ms \
  LOCAL_DEALS_ES_CONNECT_TIMEOUT=500ms \
  LOCAL_DEALS_ES_SOCKET_TIMEOUT=800ms \
  "$JAVA_HOME/bin/java" -jar "$JAR" >"$log" 2>&1 &
  local pid=$!
  printf '%s\n' "$pid" >"${ARTIFACT_DIR}/application-${instance}.pid"
  if [[ "$instance" == 1 ]]; then APP1_PID="$pid"; else APP2_PID="$pid"; fi
  wait_for_app "$pid" "http://${M5D_APP_HOST}:${management_port}" "$log"
}

seed_fixtures() {
  [[ "$(mysql_exec 'SELECT DATABASE();')" == "$SCHEMA" ]] || fail "schema identity mismatch"
  mysql_exec "DELETE FROM tb_voucher_order WHERE voucher_id BETWEEN 9011 AND 9019;
    INSERT IGNORE INTO tb_voucher(id,shop_id,title,sub_title,rules,pay_value,actual_value,type,status) VALUES
    (9011,1,'M5D C0','isolated','isolated',1,1,1,1),(9012,1,'M5D C1','isolated','isolated',1,1,1,1),
    (9013,1,'M5D C2','isolated','isolated',1,1,1,1),(9014,1,'M5D C3','isolated','isolated',1,1,1,1),
    (9015,1,'M5D X1','isolated','isolated',1,1,1,1),(9016,1,'M5D F1','isolated','isolated',1,1,1,1),
    (9017,1,'M5D F4','isolated','isolated',1,1,1,1),(9018,1,'M5D F4 overload','isolated','isolated',1,1,1,1),
    (9019,1,'M5D F5','isolated','isolated',1,1,1,1);
    INSERT INTO tb_seckill_voucher(voucher_id,stock,begin_time,end_time) VALUES
    (9011,1000,NOW()-INTERVAL 1 HOUR,NOW()+INTERVAL 1 HOUR),(9012,1000,NOW()-INTERVAL 1 HOUR,NOW()+INTERVAL 1 HOUR),
    (9013,1000,NOW()-INTERVAL 1 HOUR,NOW()+INTERVAL 1 HOUR),(9014,1000,NOW()-INTERVAL 1 HOUR,NOW()+INTERVAL 1 HOUR),
    (9015,1000,NOW()-INTERVAL 1 HOUR,NOW()+INTERVAL 1 HOUR),(9016,1000,NOW()-INTERVAL 1 HOUR,NOW()+INTERVAL 1 HOUR),
    (9017,1000,NOW()-INTERVAL 1 HOUR,NOW()+INTERVAL 1 HOUR),(9018,1000,NOW()-INTERVAL 1 HOUR,NOW()+INTERVAL 1 HOUR),
    (9019,1000,NOW()-INTERVAL 1 HOUR,NOW()+INTERVAL 1 HOUR)
    ON DUPLICATE KEY UPDATE stock=VALUES(stock),begin_time=VALUES(begin_time),end_time=VALUES(end_time);" >/dev/null
  redis_cli EVAL "for i=1,700 do redis.call('HSET','login:token:m5d-u-'..i,'id',i,'nickName','m5d-'..i,'icon',''); redis.call('EXPIRE','login:token:m5d-u-'..i,7200) end return 700" 0 >/dev/null
  redis_cli EVAL "local p={'traffic:seckill:{90*}:*','seckill:reservation:90*','seckill:order:90*','seckill:order:status:*','login:code:*','login:code:rate:*','admin:login:failure:*','admin:login:ip-attempt:*'}; for _,x in ipairs(p) do local k=redis.call('KEYS',x); for _,v in ipairs(k) do redis.call('DEL',v) end end; redis.call('DEL','seckill:order:processing'); return 1" 0 >/dev/null
  local now begin_at end_at voucher
  now="$(redis_cli --raw TIME | head -n 1)"
  begin_at=$((now - 3600))
  end_at=$((now + 3600))
  for voucher in $(seq 9011 9019); do
    redis_cli SET "seckill:stock:${voucher}" 1000 >/dev/null
    redis_cli HSET "seckill:meta:${voucher}" status ACTIVE beginAt "$begin_at" endAt "$end_at" >/dev/null
  done
}

prom_sum() {
  local metric="$1" labels="$2" url value total=0
  for url in "$MGMT1_URL" "$MGMT2_URL"; do
    value="$(curl --fail --silent "${url}/actuator/prometheus" | awk -v metric="$metric" -v labels="$labels" '
      index($1, metric "{") == 1 && index($1, labels) > 0 { total += $2 }
      END { printf "%.0f", total + 0 }
    ')"
    total=$((total + value))
  done
  printf '%s\n' "$total"
}

probe() {
  local scenario="$1"
  shift
  python3 "$PROBE_SCRIPT" --output "${ARTIFACT_DIR}/${scenario}" "$@"
}

align_redis_window() {
  local second remainder
  while true; do
    second="$(redis_cli --raw TIME | head -n 1)"
    remainder=$((second % 10))
    if [[ "$remainder" -le 1 ]]; then
      return 0
    fi
    sleep 1
  done
}

assert_summary() {
  local summary="$1" expression="$2"
  python3 - "$summary" "$expression" <<'PY'
import json, sys
s = json.loads(sys.argv[1])
if not eval(sys.argv[2], {"__builtins__": {}, "s": s, "sum": sum}, {}):
    raise SystemExit("scenario gate failed: {} :: {}".format(sys.argv[2], sys.argv[1]))
PY
}

record() {
  local scenario="$1" source="$2" status="$3" detail="$4"
  printf '%s,%s,%s,"%s"\n' "$scenario" "$source" "$status" "${detail//\"/\"\"}" >>"$SUMMARY_CSV"
}

topic_depth() {
  local topic="$1" broker="m5d-${M5D_RUN_ID}-broker"
  local namesrv="m5d-${M5D_RUN_ID}-namesrv:9876" topics
  topics="$(docker exec "$broker" sh mqadmin topicList -n "$namesrv" 2>/dev/null)" || return 1
  if ! printf '%s\n' "$topics" | grep -Fxq "$topic"; then
    printf '0\n'
    return 0
  fi
  docker exec "$broker" sh mqadmin topicStatus -n "$namesrv" -t "$topic" 2>/dev/null | awk '
    $2 ~ /^[0-9]+$/ && $3 ~ /^[0-9]+$/ && $4 ~ /^[0-9]+$/ { depth += $4 - $3; found = 1 }
    END { if (found) printf "%d\n", depth + 0; else exit 1 }
  '
}

consumer_lags() {
  local topic="$1" group="$2" progress lags dlq
  progress="$(stack consumer-progress "$group")"
  lags="$(printf '%s\n' "$progress" | awk -v topic="$topic" -v retry="%RETRY%${group}" '
    $1 == topic { main += $6; found = 1 }
    $1 == retry { retried += $6 }
    END { if (found) printf "%d %d", main + 0, retried + 0 }
  ')"
  [[ "$lags" =~ ^[0-9]+\ [0-9]+$ ]] || fail "unable to parse RocketMQ lag for ${group}"
  dlq="$(topic_depth "%DLQ%${group}")" || fail "unable to read DLQ depth for ${group}"
  printf '%s %s\n' "$lags" "$dlq"
}

wait_for_group_lag() {
  local topic="$1" group="$2" expected_main="$3" attempt lags main_lag
  for attempt in $(seq 1 90); do
    lags="$(consumer_lags "$topic" "$group")"
    read -r main_lag _ <<<"$lags"
    if [[ "$main_lag" -eq "$expected_main" ]]; then
      printf '%s\n' "$lags"
      return 0
    fi
    sleep 1
  done
  return 1
}

prom_gauge() {
  local metric="$1" value
  value="$(curl --fail --silent "$MGMT1_URL/actuator/prometheus" | awk -v metric="$metric" '
    $1 == metric && !found { print $2; found = 1 }
    END { if (!found) exit 1 }
  ')" || value=NA
  printf '%s\n' "$value"
}

prom_sum_gauge() {
  local metric="$1" url value total=0 found=0
  for url in "$MGMT1_URL" "$MGMT2_URL"; do
    value="$(curl --fail --silent "$url/actuator/prometheus" | awk -v metric="$metric" '
      index($1, metric "{") == 1 { total += $2; found = 1 }
      END { if (found) print total; else exit 1 }
    ')" || value=NA
    if [[ "$value" != NA ]]; then total="$(awk -v a="$total" -v b="$value" 'BEGIN { print a+b }')"; found=1; fi
  done
  if [[ "$found" -eq 1 ]]; then printf '%s\n' "$total"; else printf 'NA\n'; fi
}

snapshot() {
  local phase="$1" seckill_lags es_lags
  curl --fail --silent "$MGMT1_URL/actuator/prometheus" >"${ARTIFACT_DIR}/prometheus-${phase}-app1.txt" || true
  curl --fail --silent "$MGMT2_URL/actuator/prometheus" >"${ARTIFACT_DIR}/prometheus-${phase}-app2.txt" || true
  seckill_lags="$(consumer_lags "$M5D_RMQ_TOPIC" "$M5D_RMQ_CONSUMER_GROUP")"
  es_lags="$(consumer_lags "$M5D_ES_SYNC_TOPIC" "$M5D_ES_SYNC_CONSUMER_GROUP")"
  read -r s_main s_retry s_dlq <<<"$seckill_lags"
  read -r e_main e_retry e_dlq <<<"$es_lags"
  printf '%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s\n' "$phase" \
    "$(prom_sum_gauge hikaricp_connections_active)" \
    "$(prom_sum_gauge hikaricp_connections_pending)" \
    "$(prom_gauge local_deals_blog_like_outbox_oldest_age_seconds)" \
    "$(prom_gauge local_deals_seckill_processing_oldest_overdue_seconds)" \
    "$s_main" "$s_retry" "$s_dlq" "$e_main" "$e_retry" "$e_dlq" >>"$SNAPSHOT_CSV"
}

assert_seckill_converged() {
  local voucher="$1" expected_orders="$2" expected_stock="$3" attempt
  local db_orders db_stock redis_stock duplicates
  local reservation_state
  for attempt in $(seq 1 60); do
    db_orders="$(mysql_exec "SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id=${voucher};")"
    if [[ "$db_orders" -eq "$expected_orders" ]]; then
      break
    fi
    sleep 1
  done
  [[ "$db_orders" -eq "$expected_orders" ]] ||
    fail "voucher ${voucher} DB orders did not converge to ${expected_orders}"
  db_stock="$(mysql_exec "SELECT stock FROM tb_seckill_voucher WHERE voucher_id=${voucher};")"
  redis_stock="$(redis_cli --raw GET "seckill:stock:${voucher}")"
  reservation_state="$(redis_cli --raw EVAL "local ids=redis.call('HVALS','seckill:reservation:${voucher}'); local success=0; local processing=0; for _,id in ipairs(ids) do if redis.call('HGET','seckill:order:status:'..id,'status') == 'SUCCESS' then success=success+1 end; if redis.call('ZSCORE','seckill:order:processing',id) then processing=processing+1 end end; return {#ids,success,processing}" 0 | paste -sd ' ' -)"
  duplicates="$(mysql_exec "SELECT COUNT(*) FROM (SELECT user_id,COUNT(*) c FROM tb_voucher_order WHERE voucher_id=${voucher} GROUP BY user_id HAVING c>1) d;")"
  [[ "$db_stock" -eq "$expected_stock" && "$redis_stock" -eq "$expected_stock" &&
      "$reservation_state" == "${expected_orders} ${expected_orders} 0" && "$duplicates" -eq 0 ]] ||
    fail "voucher ${voucher} stock/reservation/SUCCESS/processing invariant failed"
}

start_consumer_row_lock() {
  local lock_log="${ARTIFACT_DIR}/f3-row-lock.log" attempt
  docker exec "m5d-${M5D_RUN_ID}-mysql" mysql \
    -uroot "-p${M5D_MYSQL_PASSWORD}" "$SCHEMA" -N -s \
    -e "START TRANSACTION; SELECT voucher_id FROM tb_seckill_voucher WHERE voucher_id=9017 FOR UPDATE; SELECT SLEEP(120); COMMIT;" \
    >"$lock_log" 2>&1 &
  F4_LOCK_PID=$!
  for attempt in $(seq 1 30); do
    F4_LOCK_CONNECTION="$(mysql_exec "SELECT ID FROM information_schema.PROCESSLIST WHERE DB='${SCHEMA}' AND INFO='SELECT SLEEP(120)' ORDER BY ID DESC LIMIT 1;")"
    if [[ "$F4_LOCK_CONNECTION" =~ ^[0-9]+$ ]]; then
      return 0
    fi
    sleep 1
  done
  fail "F3 deterministic row-lock fixture did not become ready"
}

release_consumer_row_lock() {
  mysql_exec "KILL ${F4_LOCK_CONNECTION};" >/dev/null
  wait "$F4_LOCK_PID" 2>/dev/null || true
  F4_LOCK_CONNECTION=""
  F4_LOCK_PID=""
}

broker_preflight() {
  stack status >"${ARTIFACT_DIR}/f5-stack-status.txt" || return 1
  local connections pid env_file pid_connections
  connections="$(ss -tnp 2>/dev/null || true)"
  printf '%s\n' "$connections" >"${ARTIFACT_DIR}/f5-connections.txt"
  for pid in "$APP1_PID" "$APP2_PID"; do
    env_file="${ARTIFACT_DIR}/f5-environ-${pid}.txt"
    tr '\0' '\n' <"/proc/${pid}/environ" | grep -E \
      '^(ROCKETMQ_NAME_SERVER|LOCAL_DEALS_SECKILL_TOPIC|LOCAL_DEALS_SECKILL_CONSUMER_GROUP|LOCAL_DEALS_ES_SYNC_TOPIC|LOCAL_DEALS_ES_SYNC_CONSUMER_GROUP)=' \
      >"$env_file" || return 1
    grep -Fxq "ROCKETMQ_NAME_SERVER=127.0.0.1:${M5D_RMQ_NAMESRV_PORT}" "$env_file" || return 1
    grep -Fxq "LOCAL_DEALS_SECKILL_TOPIC=${M5D_RMQ_TOPIC}" "$env_file" || return 1
    grep -Fxq "LOCAL_DEALS_SECKILL_CONSUMER_GROUP=${M5D_RMQ_CONSUMER_GROUP}" "$env_file" || return 1
    grep -Fxq "LOCAL_DEALS_ES_SYNC_TOPIC=${M5D_ES_SYNC_TOPIC}" "$env_file" || return 1
    grep -Fxq "LOCAL_DEALS_ES_SYNC_CONSUMER_GROUP=${M5D_ES_SYNC_CONSUMER_GROUP}" "$env_file" || return 1
    pid_connections="$(printf '%s\n' "$connections" | grep -F "pid=${pid}," || true)"
    [[ "$pid_connections" == *":${M5D_RMQ_NAMESRV_PORT}"* ]] || return 1
    [[ "$pid_connections" == *":${M5D_RMQ_BROKER_PORT}"* ]] || return 1
    [[ "$pid_connections" != *":9876"* ]] || return 1
  done
  docker port "m5d-${M5D_RUN_ID}-namesrv" 9876/tcp | grep -Fxq "127.0.0.1:${M5D_RMQ_NAMESRV_PORT}" || return 1
  docker port "m5d-${M5D_RUN_ID}-broker" "${M5D_RMQ_BROKER_PORT}/tcp" | \
    grep -Fxq "127.0.0.1:${M5D_RMQ_BROKER_PORT}" || return 1
}

mkdir -p "$ARTIFACT_DIR"
SNAPSHOT_CSV="${ARTIFACT_DIR}/metric-snapshots.csv"
if [[ "${M5D_ONLY_F5:-false}" != true ]]; then
  printf 'scenario,evidence_source,status,detail\n' >"$SUMMARY_CSV"
  printf 'phase,hikari_active,hikari_pending,outbox_oldest_age_seconds,processing_oldest_overdue_seconds,seckill_main_lag,seckill_retry_lag,seckill_dlq_depth,es_main_lag,es_retry_lag,es_dlq_depth\n' >"$SNAPSHOT_CSV"
else
  [[ -f "$SUMMARY_CSV" && -f "$SNAPSHOT_CSV" ]] || fail "M5D_ONLY_F5 requires an existing formal run"
fi
stack status | tee "${ARTIFACT_DIR}/stack-status-before.txt"
"$MAVEN_BIN" -DskipTests package >"${ARTIFACT_DIR}/package.log" 2>&1
start_application 1 "$M5D_APP1_PORT" "$M5D_MANAGEMENT1_PORT"
seed_fixtures
start_application 2 "$M5D_APP2_PORT" "$M5D_MANAGEMENT2_PORT"
[[ "$APP1_PID" != "$APP2_PID" ]] || fail "two JVMs must have distinct PIDs"
sleep 17
snapshot baseline

if [[ "${M5D_ONLY_F5:-false}" != true ]]; then
# F1 Redis: authentication/seckill fail closed while safe reads use the bounded DB fallback.
redis_cli DEL cache:shop:1 >/dev/null
stack redis-pause
REDIS_RECOVERY=unpause
sleep 17
f1_otp="$(probe f1-otp --url "${APP1_URL}/user/code?phone=13800{index:06d}" --method POST --count 5 --parallel 5 --ip-mode unique)"
f1_seckill="$(probe f1-seckill --url "${APP1_URL}/voucher-order/seckill/9016" --method POST --count 5 --parallel 5 --token-base 401 --ip-mode unique)"
f1_shop="$(probe f1-shop --url "${APP1_URL}/shop/1" --count 5 --parallel 5)"
for summary in "$f1_otp" "$f1_seckill"; do
  assert_summary "$summary" 's["transport_errors"] == 0 and s["status_counts"].get("503") == 5 and s["max_ms"] < 2000'
done
assert_summary "$f1_shop" 's["transport_errors"] == 0 and s["status_counts"].get("200") == 5 and s["max_ms"] < 2000'
snapshot f1-redis-fault
f1_recovery_started="$(date +%s%3N)"
stack redis-unpause
REDIS_RECOVERY=none
for attempt in $(seq 1 30); do redis_cli PING >/dev/null 2>&1 && break; sleep 1; done
f1_recovery_ms=$(( $(date +%s%3N) - f1_recovery_started ))
sleep 17
snapshot f1-redis-recovered
record F1 real-fault PASS "otp=${f1_otp};seckill=${f1_seckill};shop=${f1_shop};processing_collector=NaN_during_fault;recovery_ms=${f1_recovery_ms};no_order_or_stock_delta"

# F2 MySQL: preserve the known Hikari boundary and expose collector failure instead of false zero.
redis_cli DEL cache:shop:1 >/dev/null
stack mysql-stop
MYSQL_STOPPED=true
sleep 17
f2="$(probe f2-mysql --url "${APP1_URL}/shop/1" --count 5 --parallel 5 --timeout 35)"
assert_summary "$f2" 's["transport_errors"] == 0 and not s["status_counts"].get("200") and sum(s["status_counts"].get(k, 0) for k in ("429", "503")) == 5'
snapshot f2-mysql-fault
f2_recovery_started="$(date +%s%3N)"
stack mysql-start
MYSQL_STOPPED=false
wait_for_app "$APP1_PID" "$MGMT1_URL" "${ARTIFACT_DIR}/application-1.log"
wait_for_app "$APP2_PID" "$MGMT2_URL" "${ARTIFACT_DIR}/application-2.log"
f2_control="$(probe f2-recovered --url "${APP1_URL}/shop/1" --count 1 --parallel 1 --timeout 5)"
assert_summary "$f2_control" 's["transport_errors"] == 0 and s["status_counts"].get("200") == 1'
f2_recovery_ms=$(( $(date +%s%3N) - f2_recovery_started ))
sleep 17
snapshot f2-mysql-recovered
record F2 real-fault PASS "fault=${f2};control=${f2_control};outbox_collector=NaN_during_fault;first_DB_leader_retains_Hikari_boundary;recovery_ms=${f2_recovery_ms};business_rows_unchanged"

# F3 consumer pause: accept first, retain PROCESSING, observe Broker/age, then converge.
read -r f3_main_before f3_retry_before f3_dlq_before \
  <<<"$(wait_for_group_lag "$M5D_RMQ_TOPIC" "$M5D_RMQ_CONSUMER_GROUP" 0)" || fail "F3 pre-existing lag"
[[ "$f3_retry_before" -eq 0 && "$f3_dlq_before" -eq 0 ]] || fail "F3 pre-existing retry/DLQ"
start_consumer_row_lock
f3_accept="$(probe f3-accept --url "${APP1_URL}/voucher-order/seckill/9017" --method POST --count 1 --parallel 1 --token-base 601 --ip-mode unique)"
assert_summary "$f3_accept" 's["transport_errors"] == 0 and s["status_counts"].get("200") == 1'
f3_order_id="$(redis_cli --raw HGET seckill:reservation:9017 601)"
[[ "$(redis_cli --raw HGET "seckill:order:status:${f3_order_id}" status)" == PROCESSING ]] || fail "F3 PROCESSING missing"
stack consumer-pause
CONSUMER_PAUSED=true
sleep 20
f3_status="$(probe f3-status --url "${APP1_URL}/voucher-order/status/${f3_order_id}" --count 1 --parallel 1 --token-base 601 --same-token)"
snapshot f3-consumer-paused
f3_resume_started="$(date +%s%3N)"
stack consumer-resume
CONSUMER_PAUSED=false
release_consumer_row_lock
assert_seckill_converged 9017 1 999
for attempt in $(seq 1 90); do
  read -r f3_main_after f3_retry_after f3_dlq_after <<<"$(consumer_lags "$M5D_RMQ_TOPIC" "$M5D_RMQ_CONSUMER_GROUP")"
  [[ "$f3_main_after" -eq 0 && "$f3_retry_after" -eq 0 && "$f3_dlq_after" -eq 0 ]] && break
  sleep 1
done
[[ "$f3_main_after" -eq 0 && "$f3_retry_after" -eq 0 && "$f3_dlq_after" -eq 0 ]] || fail "F3 lag did not converge"
f3_recovery_ms=$(( $(date +%s%3N) - f3_resume_started ))
sleep 17
snapshot f3-consumer-recovered
record F3 real-rmq PASS "accepted=${f3_accept};status=${f3_status};paused_main_retry_dlq=see_metric_snapshot;recovery_ms=${f3_recovery_ms};orders=reservation=SUCCESS=1;db_stock=redis_stock=999;processing=duplicates=negative_stock=0"

# F4 ES: a real RocketMQ delivery fails and retries; this is consumer-level, not Canal E2E.
es_payload='{"table":"tb_shop","type":"INSERT","isDdl":false,"data":[{"id":"990001","name":"M5D replay","type_id":"1","address":"isolated","x":"120.15","y":"30.33","avg_price":"100","sold":"0","score":"40"}]}'
stack es-pause
ES_RECOVERY=unpause
f4_search="$(probe f4-search --url "${APP1_URL}/shop/search?keyword=m5d&current=1" --count 5 --parallel 5 --timeout 2.5)"
assert_summary "$f4_search" 's["transport_errors"] == 0 and sum(s["status_counts"].get(k, 0) for k in ("429", "503")) == 5'
docker exec "m5d-${M5D_RUN_ID}-broker" sh mqadmin sendMessage \
  -n "m5d-${M5D_RUN_ID}-namesrv:9876" -t "$M5D_ES_SYNC_TOPIC" -p "$es_payload" \
  >"${ARTIFACT_DIR}/f4-send-message.txt"
sleep 5
snapshot f4-es-fault
f4_recovery_started="$(date +%s%3N)"
stack es-unpause
ES_RECOVERY=none
for attempt in $(seq 1 90); do
  es_name="$(curl --silent "http://127.0.0.1:${M5D_ES_PORT}/shop_index/_doc/990001" | sed -n 's/.*"name":"\([^"]*\)".*/\1/p')"
  read -r f4_main f4_retry f4_dlq <<<"$(consumer_lags "$M5D_ES_SYNC_TOPIC" "$M5D_ES_SYNC_CONSUMER_GROUP")"
  [[ "$es_name" == "M5D replay" && "$f4_main" -eq 0 && "$f4_retry" -eq 0 && "$f4_dlq" -eq 0 ]] && break
  sleep 1
done
[[ "$es_name" == "M5D replay" && "$f4_main" -eq 0 && "$f4_retry" -eq 0 && "$f4_dlq" -eq 0 ]] || fail "F4 ES replay did not converge"
docker exec "m5d-${M5D_RUN_ID}-broker" sh mqadmin sendMessage \
  -n "m5d-${M5D_RUN_ID}-namesrv:9876" -t "$M5D_ES_SYNC_TOPIC" -p "$es_payload" \
  >"${ARTIFACT_DIR}/f4-idempotent-replay.txt"
sleep 3
es_count="$(curl --fail --silent "http://127.0.0.1:${M5D_ES_PORT}/shop_index/_count?q=_id:990001" | sed -n 's/.*"count":\([0-9]*\).*/\1/p')"
[[ "$es_count" -eq 1 ]] || fail "F4 replay created duplicate ES documents"
f4_recovery_ms=$(( $(date +%s%3N) - f4_recovery_started ))
snapshot f4-es-recovered
record F4 real-rmq-es PASS "http=${f4_search};failure_then_retry=Prometheus_and_Broker;recovery_ms=${f4_recovery_ms};document_id=990001;document_count=1;boundary=consumer-level_not_Canal_E2E;mysql_rows_unchanged"
fi

# F5 executes automatically only when every strict identity/environment/PID/TCP gate passes.
if broker_preflight; then
  printf 'PASS\n' >"${ARTIFACT_DIR}/f5-preflight.txt"
  before_stock="$(redis_cli --raw GET seckill:stock:9019)"
  before_orders="$(mysql_exec 'SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id=9019;')"
  before_topic_depth="$(topic_depth "$M5D_RMQ_TOPIC")"
  stack broker-stop
  BROKER_STOPPED=true
  f5="$(probe f5-broker --url "${APP1_URL}/voucher-order/seckill/9019" --method POST --count 20 --parallel 20 --token-base 401 --ip-mode unique --timeout 2.5)"
  assert_summary "$f5" 's["transport_errors"] == 0 and s["status_counts"].get("503") == 20 and s["max_ms"] < 2000'
  [[ "$(redis_cli --raw GET seckill:stock:9019)" == "$before_stock" ]] || fail "F5 stock changed"
  [[ "$(redis_cli HLEN seckill:reservation:9019)" -eq 0 ]] || fail "F5 reservation changed"
  [[ "$(mysql_exec 'SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id=9019;')" == "$before_orders" ]] || fail "F5 DB order changed"
  stack broker-start
  BROKER_STOPPED=false
  after_topic_depth="$(topic_depth "$M5D_RMQ_TOPIC")"
  [[ "$after_topic_depth" -eq "$before_topic_depth" ]] || fail "F5 Broker visible messages changed"
  snapshot f5-broker-recovered
  record F5 real-fault PASS "$f5; requests=20;stock/reservation/processing/db/broker_visible_delta=0"
else
  printf 'FAIL\n' >"${ARTIFACT_DIR}/f5-preflight.txt"
  record F5 strict-preflight BLOCKED "requests=0;label/port/topic/group/sentinel/environment/PID_TCP preflight incomplete;broker_not_stopped"
fi

snapshot final
mysql_exec "SELECT voucher_id,stock FROM tb_seckill_voucher WHERE voucher_id BETWEEN 9011 AND 9019 ORDER BY voucher_id;" \
  >"${ARTIFACT_DIR}/mysql-stock-after.txt"
redis_cli --scan --pattern 'traffic:seckill:{90*}:*' >"${ARTIFACT_DIR}/redis-traffic-keys-after.txt"
stack status | tee "${ARTIFACT_DIR}/stack-status-after.txt"
echo "M5D reliability scenarios completed. Summary: ${SUMMARY_CSV} snapshots: ${SNAPSHOT_CSV}"

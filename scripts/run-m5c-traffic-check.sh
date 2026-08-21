#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STACK_SCRIPT="${PROJECT_DIR}/scripts/m5c-isolated-stack.sh"
PROBE_SCRIPT="${PROJECT_DIR}/scripts/m5c-http-probe.py"
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
  echo "M5C traffic check failed: $*" >&2
  exit 1
}

require_env() {
  local name="$1"
  [[ -n "${!name:-}" ]] || fail "${name} must be set explicitly"
}

for name in M5C_RUN_ID M5C_APP_HOST M5C_APP1_PORT M5C_MANAGEMENT1_PORT \
    M5C_APP2_PORT M5C_MANAGEMENT2_PORT M5C_MYSQL_PORT M5C_MYSQL_PASSWORD \
    M5C_REDIS_PORT M5C_REDIS_PASSWORD M5C_ES_PORT M5C_RMQ_NAMESRV_PORT \
    M5C_RMQ_BROKER_PORT M5C_RMQ_TOPIC M5C_RMQ_CONSUMER_GROUP \
    M5C_RMQ_PRODUCER_GROUP; do
  require_env "$name"
done
[[ "${M5C_ISOLATED:-}" == "true" ]] || fail "set M5C_ISOLATED=true"
[[ "$M5C_APP_HOST" == "127.0.0.1" ]] || fail "applications must bind to 127.0.0.1"
[[ -x "$MAVEN_BIN" && -x "$JAVA_HOME/bin/java" ]] || fail "Java 8/Maven toolchain missing"
for command in curl docker python3 ss; do
  command -v "$command" >/dev/null 2>&1 || fail "missing command: $command"
done

export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"
export M5C_REDIS_HOST=127.0.0.1
SCHEMA="m5c_${M5C_RUN_ID//-/_}"
ARTIFACT_DIR="${PROJECT_DIR}/benchmark/m5c/${M5C_RUN_ID}"
SUMMARY_CSV="${ARTIFACT_DIR}/scenario-summary.csv"
APP1_URL="http://${M5C_APP_HOST}:${M5C_APP1_PORT}"
APP2_URL="http://${M5C_APP_HOST}:${M5C_APP2_PORT}"
MGMT1_URL="http://${M5C_APP_HOST}:${M5C_MANAGEMENT1_PORT}"
MGMT2_URL="http://${M5C_APP_HOST}:${M5C_MANAGEMENT2_PORT}"
JAR="${PROJECT_DIR}/target/local-deals-service-0.0.1-SNAPSHOT.jar"

stack() {
  "$STACK_SCRIPT" "$1"
}

redis_cli() {
  docker exec "m5c-${M5C_RUN_ID}-redis" redis-cli \
    -a "$M5C_REDIS_PASSWORD" --no-auth-warning "$@"
}

mysql_exec() {
  docker exec "m5c-${M5C_RUN_ID}-mysql" mysql \
    -uroot "-p${M5C_MYSQL_PASSWORD}" "$SCHEMA" -N -s -e "$1"
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
  LOCAL_DEALS_DATASOURCE_URL="jdbc:mysql://127.0.0.1:${M5C_MYSQL_PORT}/${SCHEMA}?useSSL=false&serverTimezone=UTC" \
  LOCAL_DEALS_DATASOURCE_USERNAME=root \
  LOCAL_DEALS_DATASOURCE_PASSWORD="$M5C_MYSQL_PASSWORD" \
  LOCAL_DEALS_REDIS_HOST=127.0.0.1 \
  LOCAL_DEALS_REDIS_PORT="$M5C_REDIS_PORT" \
  LOCAL_DEALS_REDIS_PASSWORD="$M5C_REDIS_PASSWORD" \
  LOCAL_DEALS_REDIS_COMMAND_TIMEOUT=500ms \
  LOCAL_DEALS_REDIS_POOL_MAX_WAIT=500ms \
  LOCAL_DEALS_MANAGEMENT_PORT="$management_port" \
  LOCAL_DEALS_MANAGEMENT_ADDRESS="$M5C_APP_HOST" \
  SERVER_ADDRESS="$M5C_APP_HOST" \
  SERVER_PORT="$app_port" \
  SPRING_ELASTICSEARCH_REST_URIS="http://127.0.0.1:${M5C_ES_PORT}" \
  ROCKETMQ_NAME_SERVER="127.0.0.1:${M5C_RMQ_NAMESRV_PORT}" \
  LOCAL_DEALS_ROCKETMQ_PRODUCER_GROUP="$M5C_RMQ_PRODUCER_GROUP" \
  LOCAL_DEALS_SECKILL_TOPIC="$M5C_RMQ_TOPIC" \
  LOCAL_DEALS_SECKILL_CONSUMER_GROUP="$M5C_RMQ_CONSUMER_GROUP" \
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
  wait_for_app "$pid" "http://${M5C_APP_HOST}:${management_port}" "$log"
}

seed_fixtures() {
  [[ "$(mysql_exec 'SELECT DATABASE();')" == "$SCHEMA" ]] || fail "schema identity mismatch"
  mysql_exec "DELETE FROM tb_voucher_order WHERE voucher_id BETWEEN 9011 AND 9019;
    INSERT IGNORE INTO tb_voucher(id,shop_id,title,sub_title,rules,pay_value,actual_value,type,status) VALUES
    (9011,1,'M5C C0','isolated','isolated',1,1,1,1),(9012,1,'M5C C1','isolated','isolated',1,1,1,1),
    (9013,1,'M5C C2','isolated','isolated',1,1,1,1),(9014,1,'M5C C3','isolated','isolated',1,1,1,1),
    (9015,1,'M5C X1','isolated','isolated',1,1,1,1),(9016,1,'M5C F1','isolated','isolated',1,1,1,1),
    (9017,1,'M5C F4','isolated','isolated',1,1,1,1),(9018,1,'M5C F4 overload','isolated','isolated',1,1,1,1),
    (9019,1,'M5C F5','isolated','isolated',1,1,1,1);
    INSERT INTO tb_seckill_voucher(voucher_id,stock,begin_time,end_time) VALUES
    (9011,1000,NOW()-INTERVAL 1 HOUR,NOW()+INTERVAL 1 HOUR),(9012,1000,NOW()-INTERVAL 1 HOUR,NOW()+INTERVAL 1 HOUR),
    (9013,1000,NOW()-INTERVAL 1 HOUR,NOW()+INTERVAL 1 HOUR),(9014,1000,NOW()-INTERVAL 1 HOUR,NOW()+INTERVAL 1 HOUR),
    (9015,1000,NOW()-INTERVAL 1 HOUR,NOW()+INTERVAL 1 HOUR),(9016,1000,NOW()-INTERVAL 1 HOUR,NOW()+INTERVAL 1 HOUR),
    (9017,1000,NOW()-INTERVAL 1 HOUR,NOW()+INTERVAL 1 HOUR),(9018,1000,NOW()-INTERVAL 1 HOUR,NOW()+INTERVAL 1 HOUR),
    (9019,1000,NOW()-INTERVAL 1 HOUR,NOW()+INTERVAL 1 HOUR)
    ON DUPLICATE KEY UPDATE stock=VALUES(stock),begin_time=VALUES(begin_time),end_time=VALUES(end_time);" >/dev/null
  redis_cli EVAL "for i=1,700 do redis.call('HSET','login:token:m5c-u-'..i,'id',i,'nickName','m5c-'..i,'icon',''); redis.call('EXPIRE','login:token:m5c-u-'..i,7200) end return 700" 0 >/dev/null
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

consumer_lags() {
  local progress lags
  progress="$(stack consumer-progress)"
  lags="$(printf '%s\n' "$progress" | awk \
    -v topic="$M5C_RMQ_TOPIC" -v retry="%RETRY%${M5C_RMQ_CONSUMER_GROUP}" '
      $1 == topic { main += $6 }
      $1 == retry { retried += $6 }
      $1 == "Diff" && $2 == "Total:" { total = $3; found = 1 }
      END { if (found) printf "%d %d %d", main + 0, retried + 0, total + 0 }
    ')"
  [[ "$lags" =~ ^[0-9]+\ [0-9]+\ [0-9]+$ ]] ||
    fail "unable to parse RocketMQ main/retry/total lag"
  printf '%s\n' "$lags"
}

wait_for_main_consumer_lag() {
  local expected="$1" attempt lags main_lag
  for attempt in $(seq 1 60); do
    lags="$(consumer_lags)"
    read -r main_lag _ <<<"$lags"
    if [[ "$main_lag" -eq "$expected" ]]; then
      printf '%s\n' "$lags"
      return 0
    fi
    sleep 1
  done
  return 1
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

start_f4_row_lock() {
  local lock_log="${ARTIFACT_DIR}/f4-row-lock.log" attempt
  docker exec "m5c-${M5C_RUN_ID}-mysql" mysql \
    -uroot "-p${M5C_MYSQL_PASSWORD}" "$SCHEMA" -N -s \
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
  fail "F4 deterministic row-lock fixture did not become ready"
}

release_f4_row_lock() {
  mysql_exec "KILL ${F4_LOCK_CONNECTION};" >/dev/null
  wait "$F4_LOCK_PID" 2>/dev/null || true
  F4_LOCK_CONNECTION=""
  F4_LOCK_PID=""
}

broker_preflight() {
  stack status >/dev/null
  local connections
  connections="$(ss -tnp 2>/dev/null || true)"
  [[ "$connections" == *":${M5C_RMQ_NAMESRV_PORT}"* ]] || return 1
  [[ "$connections" == *":${M5C_RMQ_BROKER_PORT}"* ]] || return 1
  [[ "$connections" != *":9876"* ]] || return 1
  grep -Fq "127.0.0.1:${M5C_RMQ_NAMESRV_PORT}" "${ARTIFACT_DIR}/application-1.log" || return 1
  grep -Fq "$M5C_RMQ_TOPIC" "${ARTIFACT_DIR}/application-1.log" || return 1
}

mkdir -p "$ARTIFACT_DIR"
printf 'scenario,evidence_source,status,detail\n' >"$SUMMARY_CSV"
stack status | tee "${ARTIFACT_DIR}/stack-status-before.txt"
"$MAVEN_BIN" -DskipTests package >"${ARTIFACT_DIR}/package.log" 2>&1
start_application 1 "$M5C_APP1_PORT" "$M5C_MANAGEMENT1_PORT"
seed_fixtures
start_application 2 "$M5C_APP2_PORT" "$M5C_MANAGEMENT2_PORT"
[[ "$APP1_PID" != "$APP2_PID" ]] || fail "two JVMs must have distinct PIDs"
curl --fail --silent "$MGMT1_URL/actuator/prometheus" >"${ARTIFACT_DIR}/prometheus-before.txt"
curl --fail --silent "$MGMT2_URL/actuator/prometheus" >"${ARTIFACT_DIR}/prometheus-before-app2.txt"

# C0: low traffic across all protected public surfaces; auth business rejects are allowed,
# infrastructure 429/503 are not.
c0_search="$(probe c0-search --url "${APP1_URL}/shop/search?keyword=%E8%8C%B6&current=1" --count 20 --parallel 4)"
assert_summary "$c0_search" 's["transport_errors"] == 0 and s["status_counts"].get("200") == 20'
c0_hot="$(probe c0-hot --url "${APP1_URL}/blog/hot?current=1" --count 20 --parallel 4)"
assert_summary "$c0_hot" 's["transport_errors"] == 0 and s["status_counts"].get("200") == 20'
c0_otp="$(probe c0-otp --url "${APP1_URL}/user/code?phone=13900{index:06d}" --method POST --count 20 --parallel 4 --ip-mode unique)"
assert_summary "$c0_otp" 's["transport_errors"] == 0 and not s["status_counts"].get("429") and not s["status_counts"].get("503")'
c0_admin="$(probe c0-admin --url "${APP1_URL}/admin/auth/login" --method POST --count 20 --parallel 4 --ip-mode unique --json-template '{"username":"m5c-{index}","password":"wrong"}')"
assert_summary "$c0_admin" 's["transport_errors"] == 0 and not s["status_counts"].get("429") and not s["status_counts"].get("503")'
c0_seckill="$(probe c0-seckill --url "${APP1_URL}/voucher-order/seckill/9011" --method POST --count 20 --parallel 8 --token-base 1 --ip-mode unique)"
assert_summary "$c0_seckill" 's["transport_errors"] == 0 and not s["status_counts"].get("429") and not s["status_counts"].get("503")'
assert_seckill_converged 9011 20 980
record C0 real-http PASS "search=${c0_search};hot=${c0_hot};otp=${c0_otp};admin=${c0_admin};seckill=${c0_seckill};orders=reservation=SUCCESS=20;db_stock=redis_stock=980;processing=duplicates=0;throughput=NA_incomparable_probe"

# C1-C3 exact target dimensions. Redis TIME alignment prevents boundary ambiguity.
align_redis_window
before_allowed="$(prom_sum local_deals_traffic_decision_total 'resource="seckill",result="allowed"')"
before_rejected="$(prom_sum local_deals_traffic_decision_total 'reason="activity",resource="seckill",result="rejected"')"
c1="$(probe c1-activity --url "${APP1_URL}/voucher-order/seckill/9012" --method POST --count 320 --parallel 64 --token-base 21 --ip-mode unique --timeout 5)"
after_allowed="$(prom_sum local_deals_traffic_decision_total 'resource="seckill",result="allowed"')"
after_rejected="$(prom_sum local_deals_traffic_decision_total 'reason="activity",resource="seckill",result="rejected"')"
[[ $((after_allowed - before_allowed)) -eq 300 && $((after_rejected - before_rejected)) -eq 20 ]] || fail "C1 exact activity limit failed"
assert_summary "$c1" 's["transport_errors"] == 0 and s["status_counts"].get("429") == 20 and s["code_counts"].get("SECKILL_RATE_LIMITED") == 20'
assert_seckill_converged 9012 300 700
record C1 real-http PASS "$c1; orders=reservation=SUCCESS=300;db_stock=redis_stock=700;processing=duplicates=0"

align_redis_window
before_allowed="$(prom_sum local_deals_traffic_decision_total 'resource="seckill",result="allowed"')"
before_rejected="$(prom_sum local_deals_traffic_decision_total 'reason="user",resource="seckill",result="rejected"')"
c2="$(probe c2-user --url "${APP1_URL}/voucher-order/seckill/9013" --method POST --count 22 --parallel 22 --token-base 501 --same-token --ip-mode unique --timeout 5)"
after_allowed="$(prom_sum local_deals_traffic_decision_total 'resource="seckill",result="allowed"')"
after_rejected="$(prom_sum local_deals_traffic_decision_total 'reason="user",resource="seckill",result="rejected"')"
[[ $((after_allowed - before_allowed)) -eq 2 && $((after_rejected - before_rejected)) -eq 20 ]] || fail "C2 exact user limit failed"
assert_seckill_converged 9013 1 999
record C2 real-http PASS "$c2; orders=reservation=SUCCESS=1;db_stock=redis_stock=999;processing=duplicates=0"

align_redis_window
before_allowed="$(prom_sum local_deals_traffic_decision_total 'resource="seckill",result="allowed"')"
before_rejected="$(prom_sum local_deals_traffic_decision_total 'reason="ip",resource="seckill",result="rejected"')"
c3="$(probe c3-ip --url "${APP1_URL}/voucher-order/seckill/9014" --method POST --count 120 --parallel 48 --token-base 1 --ip-mode same --ip-base 77 --timeout 5)"
after_allowed="$(prom_sum local_deals_traffic_decision_total 'resource="seckill",result="allowed"')"
after_rejected="$(prom_sum local_deals_traffic_decision_total 'reason="ip",resource="seckill",result="rejected"')"
[[ $((after_allowed - before_allowed)) -eq 100 && $((after_rejected - before_rejected)) -eq 20 ]] || fail "C3 exact IP limit failed"
assert_seckill_converged 9014 100 900
record C3 real-http PASS "$c3; orders=reservation=SUCCESS=100;db_stock=redis_stock=900;processing=duplicates=0"

# C4-C6 are deterministic latch/exception contracts; dependency behavior is fault-tested below.
"$MAVEN_BIN" -Dtest=LocalReadBulkheadTest,SingleFlightLoaderTest,SearchTrafficContractTest test \
  >"${ARTIFACT_DIR}/c4-c6-tests.log" 2>&1
record C4 java-latch PASS "DB_READ max=4, overflow/release/interrupt contract"
record C5 java-latch PASS "1 leader + bounded followers, no cancel/no second load"
record C6 java-contract PASS "shared SEARCH compartment, overload and ES mapping"

# X1: both JVMs share the same Redis activity counter.
align_redis_window
before_allowed="$(prom_sum local_deals_traffic_decision_total 'resource="seckill",result="allowed"')"
x1="$(probe x1-dual-seckill --url "${APP1_URL}/voucher-order/seckill/9015" --url "${APP2_URL}/voucher-order/seckill/9015" --method POST --count 320 --parallel 64 --token-base 21 --ip-mode unique --timeout 5)"
after_allowed="$(prom_sum local_deals_traffic_decision_total 'resource="seckill",result="allowed"')"
[[ $((after_allowed - before_allowed)) -eq 300 ]] || fail "X1 shared activity limit exceeded 300"
assert_seckill_converged 9015 300 700
record X1 dual-jvm PASS "$x1; allowed_delta=300;orders=reservation=SUCCESS=300;db_stock=redis_stock=700;processing=duplicates=0"

# X2: per-JVM singleflight explicitly permits one DB fallback in each process.
redis_cli DEL cache:shop:1 >/dev/null
before_db="$(prom_sum local_deals_cache_access_total 'resource="shop_detail",result="db_success"')"
x2="$(probe x2-dual-cold --url "${APP1_URL}/shop/1" --url "${APP2_URL}/shop/1" --count 40 --parallel 40)"
after_db="$(prom_sum local_deals_cache_access_total 'resource="shop_detail",result="db_success"')"
db_delta=$((after_db - before_db))
[[ "$db_delta" -le 2 ]] || fail "X2 DB fallback exceeded two JVMs: $db_delta"
record X2 dual-jvm PASS "$x2; db_fallback_delta=${db_delta}; boundary=per-jvm"

# F1: Redis failure must close auth/seckill and keep M5B shop DB fallback usable.
stack redis-pause
REDIS_RECOVERY=unpause
f1_otp="$(probe f1-otp --url "${APP1_URL}/user/code?phone=13800{index:06d}" --method POST --count 20 --parallel 20 --ip-mode unique)"
f1_admin="$(probe f1-admin --url "${APP1_URL}/admin/auth/login" --method POST --count 20 --parallel 20 --ip-mode unique --json-template '{"username":"f1-{index}","password":"wrong"}')"
f1_seckill="$(probe f1-seckill --url "${APP1_URL}/voucher-order/seckill/9016" --method POST --count 20 --parallel 20 --token-base 401 --ip-mode unique)"
f1_shop="$(probe f1-shop-control --url "${APP1_URL}/shop/1" --count 20 --parallel 20)"
for summary in "$f1_otp" "$f1_admin" "$f1_seckill"; do
  assert_summary "$summary" 's["transport_errors"] == 0 and s["status_counts"].get("503") == 20 and s["max_ms"] < 2000'
done
assert_summary "$f1_shop" 's["transport_errors"] == 0 and s["status_counts"].get("200") == 20 and s["max_ms"] < 2000'
record F1 real-fault PASS "otp=${f1_otp};admin=${f1_admin};seckill=${f1_seckill};shop=${f1_shop}"
stack redis-unpause
REDIS_RECOVERY=none

# F3: no MySQL LIKE fallback exists; SEARCH returns only bounded 429/503.
stack es-pause
ES_RECOVERY=unpause
f3="$(probe f3-es --url "${APP1_URL}/shop/search?keyword=%E8%8C%B6&current=1" --url "${APP1_URL}/blog/search?keyword=%E7%BE%8E%E9%A3%9F&current=1" --count 40 --parallel 40 --timeout 2.5)"
assert_summary "$f3" 's["transport_errors"] == 0 and sum(s["status_counts"].get(k, 0) for k in ("429", "503")) == 40 and s["max_ms"] < 2000'
record F3 real-fault PASS "$f3; mysql_like_fallback=forbidden_by_call-chain"
stack es-unpause
ES_RECOVERY=none

# F4: pause only the dedicated consumer group, accept one order, overload entry, then converge.
read -r f4_main_lag_before f4_retry_lag_before f4_total_lag_before \
  <<<"$(wait_for_main_consumer_lag 0)" || fail "F4 pre-existing main-topic Broker lag"
[[ "$f4_retry_lag_before" -eq 0 && "$f4_total_lag_before" -eq 0 ]] ||
  fail "F4 pre-existing retry or total Broker lag"
start_f4_row_lock
f4_accept="$(probe f4-accept --url "${APP1_URL}/voucher-order/seckill/9017" --method POST --count 1 --parallel 1 --token-base 601 --ip-mode unique)"
assert_summary "$f4_accept" 's["transport_errors"] == 0 and s["status_counts"].get("200") == 1 and s["code_counts"].get("null") == 1'
[[ "$(redis_cli HLEN seckill:reservation:9017)" -eq 1 ]] || fail "F4 accepted reservation missing"
f4_order_id="$(redis_cli --raw HGET seckill:reservation:9017 601)"
[[ "$(redis_cli --raw HGET "seckill:order:status:${f4_order_id}" status)" == PROCESSING ]] ||
  fail "F4 accepted order did not remain PROCESSING before consumer pause"
stack consumer-pause
CONSUMER_PAUSED=true
f4_overload="$(probe f4-overload --url "${APP1_URL}/voucher-order/seckill/9018" --url "${APP2_URL}/voucher-order/seckill/9018" --method POST --count 320 --parallel 64 --token-base 21 --ip-mode unique --timeout 5)"
assert_summary "$f4_overload" 's["transport_errors"] == 0 and s["status_counts"].get("200") == 300 and s["status_counts"].get("429") == 20'
[[ "$(redis_cli HLEN seckill:reservation:9017)" -eq 1 ]] || fail "F4 PROCESSING reservation changed under overload"
[[ "$(redis_cli --raw HGET "seckill:order:status:${f4_order_id}" status)" == PROCESSING ]] ||
  fail "F4 existing PROCESSING order changed under entry overload"
read -r f4_main_lag_before f4_retry_lag_before f4_total_lag_before \
  <<<"$(wait_for_main_consumer_lag 301)" || fail "F4 main-topic Broker lag did not reach 301"
stack consumer-progress >"${ARTIFACT_DIR}/f4-consumer-progress-paused.txt"
f4_resume_ms="$(date +%s%3N)"
stack consumer-resume
CONSUMER_PAUSED=false
release_f4_row_lock
for attempt in $(seq 1 120); do
  f4_order_count="$(mysql_exec 'SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id IN (9017,9018);')"
  f4_processing="$(redis_cli ZCARD seckill:order:processing)"
  read -r f4_main_lag_after f4_retry_lag_after f4_total_lag_after <<<"$(consumer_lags)"
  if [[ "$f4_order_count" -eq 301 && "$f4_processing" -eq 0 &&
      "$f4_main_lag_after" -eq 0 && "$f4_retry_lag_after" -eq 0 &&
      "$f4_total_lag_after" -eq 0 ]]; then
    break
  fi
  sleep 1
done
[[ "$f4_order_count" -eq 301 && "$f4_processing" -eq 0 &&
    "$f4_main_lag_after" -eq 0 && "$f4_retry_lag_after" -eq 0 &&
    "$f4_total_lag_after" -eq 0 ]] ||
  fail "F4 backlog did not fully converge"
f4_convergence_ms=$(( $(date +%s%3N) - f4_resume_ms ))
f4_success="$(redis_cli EVAL "local count=0; for _,v in ipairs({'9017','9018'}) do local ids=redis.call('HVALS','seckill:reservation:'..v); for _,id in ipairs(ids) do if redis.call('HGET','seckill:order:status:'..id,'status') ~= 'SUCCESS' then return -1 end; count=count+1 end end; return count" 0)"
[[ "$f4_success" -eq 301 ]] || fail "F4 reservations did not all reach SUCCESS"
stack consumer-progress >"${ARTIFACT_DIR}/f4-consumer-progress-recovered.txt"
duplicates="$(mysql_exec 'SELECT COUNT(*) FROM (SELECT user_id,voucher_id,COUNT(*) c FROM tb_voucher_order GROUP BY user_id,voucher_id HAVING c>1) d;')"
negative_stock="$(mysql_exec 'SELECT COUNT(*) FROM tb_seckill_voucher WHERE stock < 0;')"
[[ "$duplicates" -eq 0 && "$negative_stock" -eq 0 ]] || fail "F4 order invariant failed"
record F4 real-rmq PASS "accepted=${f4_accept};overload=${f4_overload};main_lag_before=${f4_main_lag_before};retry_lag_before=${f4_retry_lag_before};total_lag_before=${f4_total_lag_before};main_lag_after=${f4_main_lag_after};retry_lag_after=${f4_retry_lag_after};total_lag_after=${f4_total_lag_after};convergence_ms=${f4_convergence_ms};orders=301;success=301;processing=0;duplicates=0;negative_stock=0"

# F2: first four licensed leaders retain the underlying JDBC/Hikari boundary. Record it honestly.
for shop in $(seq 1 20); do redis_cli DEL "cache:shop:${shop}" >/dev/null; done
stack mysql-stop
MYSQL_STOPPED=true
f2_distinct="$(probe f2-distinct --url "${APP1_URL}/shop/{index}" --count 20 --parallel 20 --timeout 35)"
f2_shared="$(probe f2-shared --url "${APP1_URL}/shop/99999991" --count 21 --parallel 21 --timeout 35)"
assert_summary "$f2_distinct" 's["transport_errors"] == 0 and not s["status_counts"].get("200") and sum(s["status_counts"].get(k, 0) for k in ("429", "503")) == 20'
assert_summary "$f2_shared" 's["transport_errors"] == 0 and not s["status_counts"].get("200") and s["status_counts"].get("503", 0) >= 20'
record F2 real-fault PASS "distinct=${f2_distinct};shared=${f2_shared};first_leaders_not_claimed_under_2s"
stack mysql-start
MYSQL_STOPPED=false
wait_for_app "$APP1_PID" "$MGMT1_URL" "${ARTIFACT_DIR}/application-1.log"
wait_for_app "$APP2_PID" "$MGMT2_URL" "${ARTIFACT_DIR}/application-2.log"

# R1 recovery probes.
r1_shop="$(probe r1-shop --url "${APP1_URL}/shop/1" --count 1 --parallel 1 --timeout 5)"
r1_search="$(probe r1-search --url "${APP1_URL}/shop/search?keyword=%E8%8C%B6&current=1" --count 1 --parallel 1 --timeout 5)"
assert_summary "$r1_shop" 's["transport_errors"] == 0 and s["status_counts"].get("200") == 1'
assert_summary "$r1_search" 's["transport_errors"] == 0 and s["status_counts"].get("200") == 1'
record R1 real-recovery PASS "shop=${r1_shop};search=${r1_search}"

# F5 remains blocked unless a human explicitly opts in after all strict checks pass.
if broker_preflight; then
  printf 'PASS\n' >"${ARTIFACT_DIR}/f5-preflight.txt"
  if [[ "${M5C_RUN_BROKER_FAULT:-false}" == true ]]; then
    before_stock="$(redis_cli --raw GET seckill:stock:9019)"
    before_orders="$(mysql_exec 'SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id=9019;')"
    stack broker-stop
    BROKER_STOPPED=true
    f5="$(probe f5-broker --url "${APP1_URL}/voucher-order/seckill/9019" --method POST --count 20 --parallel 20 --token-base 401 --ip-mode unique --timeout 2.5)"
    assert_summary "$f5" 's["transport_errors"] == 0 and s["status_counts"].get("503") == 20 and s["max_ms"] < 2000'
    [[ "$(redis_cli --raw GET seckill:stock:9019)" == "$before_stock" ]] || fail "F5 stock changed"
    [[ "$(redis_cli HLEN seckill:reservation:9019)" -eq 0 ]] || fail "F5 reservation changed"
    [[ "$(mysql_exec 'SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id=9019;')" == "$before_orders" ]] || fail "F5 DB order changed"
    stack broker-start
    BROKER_STOPPED=false
    record F5 real-fault PASS "$f5; stock/reservation/db delta=0"
  else
    record F5 strict-preflight BLOCKED "preflight passed; destructive probe not opted in with M5C_RUN_BROKER_FAULT=true"
  fi
else
  printf 'FAIL\n' >"${ARTIFACT_DIR}/f5-preflight.txt"
  record F5 strict-preflight BLOCKED "identity/connection/topic/group/log preflight incomplete; no broker stop or probe sent"
fi

curl --fail --silent "$MGMT1_URL/actuator/prometheus" >"${ARTIFACT_DIR}/prometheus-after.txt"
curl --fail --silent "$MGMT2_URL/actuator/prometheus" >"${ARTIFACT_DIR}/prometheus-after-app2.txt"
mysql_exec "SELECT voucher_id,stock FROM tb_seckill_voucher WHERE voucher_id BETWEEN 9011 AND 9019 ORDER BY voucher_id;" \
  >"${ARTIFACT_DIR}/mysql-stock-after.txt"
redis_cli --scan --pattern 'traffic:seckill:{90*}:*' >"${ARTIFACT_DIR}/redis-traffic-keys-after.txt"
stack status | tee "${ARTIFACT_DIR}/stack-status-after.txt"
echo "M5C traffic scenarios completed. Summary: ${SUMMARY_CSV}"

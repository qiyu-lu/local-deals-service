#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STACK_SCRIPT="${PROJECT_DIR}/scripts/m5b-isolated-stack.sh"
MAVEN_BIN="${MAVEN_BIN:-/home/sd101t/.m2/wrapper/dists/apache-maven-3.9.11/a2d47e15/bin/mvn}"
JAVA_HOME="${JAVA_HOME:-/home/sd101t/.jdks/dragonwell-ex-1.8.0_472}"
APP_PID=""
REDIS_RECOVERY="none"
MYSQL_STOPPED="false"
TYPE_BACKUP_ACTIVE="false"

fail() {
  echo "M5B cache check failed: $*" >&2
  exit 1
}

require_env() {
  local name="$1"
  [[ -n "${!name:-}" ]] || fail "${name} must be set explicitly"
}

for name in M5B_RUN_ID M5B_APP_HOST M5B_APP_PORT M5B_MANAGEMENT_PORT \
    M5B_MYSQL_PORT M5B_MYSQL_PASSWORD M5B_REDIS_PORT M5B_REDIS_PASSWORD \
    M5B_ES_PORT M5B_RMQ_NAMESRV_PORT M5B_RMQ_BROKER_PORT; do
  require_env "$name"
done
[[ "${M5B_ISOLATED:-}" == "true" ]] || fail "set M5B_ISOLATED=true"
[[ "$M5B_APP_HOST" == "127.0.0.1" ]] || fail "application must bind to 127.0.0.1"
[[ -x "$MAVEN_BIN" ]] || fail "Maven is not executable: $MAVEN_BIN"
[[ -x "$JAVA_HOME/bin/java" ]] || fail "Java 8 is not executable: $JAVA_HOME/bin/java"
command -v curl >/dev/null || fail "curl is required"
command -v python3 >/dev/null || fail "python3 is required"
command -v docker >/dev/null || fail "docker is required"

export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"
export M5B_REDIS_HOST="127.0.0.1"
export M5B_MYSQL_USER="root"
export M5B_MYSQL_URL="jdbc:mysql://127.0.0.1:${M5B_MYSQL_PORT}/m5b_${M5B_RUN_ID//-/_}?useSSL=false&serverTimezone=UTC"

ARTIFACT_DIR="${PROJECT_DIR}/benchmark/m5b/${M5B_RUN_ID}"
SUMMARY_CSV="${ARTIFACT_DIR}/scenario-summary.csv"
APP_LOG="${ARTIFACT_DIR}/application.log"
BASE_URL="http://${M5B_APP_HOST}:${M5B_APP_PORT}"
MANAGEMENT_URL="http://${M5B_APP_HOST}:${M5B_MANAGEMENT_PORT}"
SHOP_ID=1
MISSING_SHOP_ID=999999991
SHOP_NAME="M5B Fixture ${M5B_RUN_ID}"
SCHEMA="m5b_${M5B_RUN_ID//-/_}"

stack() {
  "$STACK_SCRIPT" "$1"
}

redis_cli() {
  docker exec "m5b-${M5B_RUN_ID}-redis" redis-cli \
    -a "$M5B_REDIS_PASSWORD" --no-auth-warning "$@"
}

mysql_exec() {
  docker exec "m5b-${M5B_RUN_ID}-mysql" mysql \
    -uroot "-p${M5B_MYSQL_PASSWORD}" "$SCHEMA" -N -s -e "$1"
}

restore_type_fixture() {
  if [[ "$TYPE_BACKUP_ACTIVE" == "true" ]]; then
    mysql_exec "INSERT INTO tb_shop_type SELECT * FROM m5b_shop_type_backup; DROP TABLE m5b_shop_type_backup;" >/dev/null || true
    TYPE_BACKUP_ACTIVE="false"
  fi
}

cleanup() {
  set +e
  if [[ "$REDIS_RECOVERY" == "unpause" ]]; then
    stack redis-unpause
  elif [[ "$REDIS_RECOVERY" == "start" ]]; then
    stack redis-start
  fi
  REDIS_RECOVERY="none"
  if [[ "$MYSQL_STOPPED" == "true" ]]; then
    stack mysql-start
    MYSQL_STOPPED="false"
  fi
  restore_type_fixture
  if [[ -n "$APP_PID" ]] && kill -0 "$APP_PID" >/dev/null 2>&1; then
    kill "$APP_PID"
    wait "$APP_PID" 2>/dev/null
  fi
}
trap cleanup EXIT INT TERM

wait_for_app() {
  local attempt
  for attempt in $(seq 1 120); do
    if curl --fail --silent "${MANAGEMENT_URL}/actuator/health/readiness" >/dev/null; then
      return 0
    fi
    if ! kill -0 "$APP_PID" >/dev/null 2>&1; then
      tail -100 "$APP_LOG" >&2 || true
      fail "application exited before readiness"
    fi
    sleep 1
  done
  tail -100 "$APP_LOG" >&2 || true
  fail "application readiness timed out"
}

start_application() {
  LOCAL_DEALS_DATASOURCE_URL="$M5B_MYSQL_URL" \
  LOCAL_DEALS_DATASOURCE_USERNAME="$M5B_MYSQL_USER" \
  LOCAL_DEALS_DATASOURCE_PASSWORD="$M5B_MYSQL_PASSWORD" \
  LOCAL_DEALS_REDIS_HOST="$M5B_REDIS_HOST" \
  LOCAL_DEALS_REDIS_PORT="$M5B_REDIS_PORT" \
  LOCAL_DEALS_REDIS_PASSWORD="$M5B_REDIS_PASSWORD" \
  LOCAL_DEALS_REDIS_COMMAND_TIMEOUT=500ms \
  LOCAL_DEALS_REDIS_POOL_MAX_WAIT=500ms \
  LOCAL_DEALS_MANAGEMENT_PORT="$M5B_MANAGEMENT_PORT" \
  LOCAL_DEALS_MANAGEMENT_ADDRESS="$M5B_APP_HOST" \
  SERVER_ADDRESS="$M5B_APP_HOST" \
  SERVER_PORT="$M5B_APP_PORT" \
  SPRING_PROFILES_ACTIVE=test \
  SPRING_ELASTICSEARCH_REST_URIS="http://127.0.0.1:${M5B_ES_PORT}" \
  ROCKETMQ_NAME_SERVER="127.0.0.1:${M5B_RMQ_NAMESRV_PORT}" \
  LOCAL_DEALS_BLOG_HOT_RANK_READ_ENABLED=true \
  LOCAL_DEALS_BLOG_HOT_RANK_REFRESH_ENABLED=true \
  "$MAVEN_BIN" -DskipTests spring-boot:run >"$APP_LOG" 2>&1 &
  APP_PID=$!
  wait_for_app
}

prom_value() {
  local metric="$1"
  local labels="$2"
  curl --fail --silent "${MANAGEMENT_URL}/actuator/prometheus" |
    awk -v metric="$metric" -v labels="$labels" '
      index($1, metric "{") == 1 && index($1, labels) > 0 { total += $2 }
      END { printf "%.0f\n", total + 0 }
    '
}

cache_count() {
  local resource="$1"
  local result="$2"
  prom_value local_deals_cache_access_total "resource=\"${resource}\",result=\"${result}\""
}

snapshot_dependencies() {
  local prefix="$1"
  mysql_exec "SHOW GLOBAL STATUS LIKE 'Queries';" >"${ARTIFACT_DIR}/${prefix}-mysql-queries.txt"
  redis_cli INFO stats | tr -d '\r' | grep '^total_commands_processed:' \
    >"${ARTIFACT_DIR}/${prefix}-redis-commands.txt"
  curl --fail --silent "${MANAGEMENT_URL}/actuator/prometheus" \
    >"${ARTIFACT_DIR}/${prefix}-prometheus.txt"
}

run_batch() {
  local scenario="$1"
  local count="$2"
  local parallel="$3"
  local url="$4"
  local validator="$5"
  local output_dir="${ARTIFACT_DIR}/${scenario}"
  mkdir -p "$output_dir"
  local started ended
  started="$(date +%s%N)"
  seq 1 "$count" | xargs -P "$parallel" -I '{}' sh -c '
    index="$1"
    url="$2"
    output_dir="$3"
    curl --silent --show-error --max-time 2.5 --output "${output_dir}/${index}.json" \
      --write-out "%{time_total}" "$url" >"${output_dir}/${index}.time" ||
      printf "transport_error" >"${output_dir}/${index}.error"
  ' sh '{}' "$url" "$output_dir"
  ended="$(date +%s%N)"
  python3 - "$output_dir" "$count" "$validator" "$started" "$ended" <<'PY'
import json, math, pathlib, statistics, sys
root = pathlib.Path(sys.argv[1])
expected = int(sys.argv[2])
validator = sys.argv[3]
started, ended = int(sys.argv[4]), int(sys.argv[5])
times, correct, errors = [], 0, 0
for index in range(1, expected + 1):
    if (root / f"{index}.error").exists():
        errors += 1
        continue
    try:
        elapsed = float((root / f"{index}.time").read_text()) * 1000.0
        body = json.loads((root / f"{index}.json").read_text())
        times.append(elapsed)
        kind, *parts = validator.split(":")
        ok = body.get("success") is True
        if kind == "shop":
            ok = ok and str(body.get("data", {}).get("id")) == parts[0]
            ok = ok and parts[1] in str(body.get("data", {}).get("name", ""))
        elif kind == "missing":
            ok = body.get("success") is False and body.get("data") is None
        elif kind == "types":
            data = body.get("data")
            ok = ok and isinstance(data, list) and len(data) > 0
            ok = ok and all(isinstance(item.get("id"), int) and item["id"] > 0 for item in data)
            ok = ok and len({item["id"] for item in data}) == len(data)
        elif kind == "empty_types":
            ok = ok and body.get("data") == []
        elif kind == "hot":
            ok = ok and isinstance(body.get("data"), list) and len(body["data"]) > 0
        correct += int(ok)
    except Exception:
        errors += 1
times.sort()
def percentile(p):
    if not times:
        return "NA"
    return f"{times[min(len(times)-1, math.ceil(len(times)*p)-1)]:.3f}"
duration = max((ended - started) / 1e9, 0.000001)
throughput = expected / duration
maximum = f"{max(times):.3f}" if times else "NA"
print(f"{correct},{expected},{errors},{percentile(.50)},{percentile(.95)},{percentile(.99)},{maximum},{throughput:.3f}")
PY
}

record() {
  local scenario="$1"
  local phase="$2"
  local stats="$3"
  local fallback="$4"
  local status="$5"
  printf '%s,%s,%s,%s,%s\n' "$scenario" "$phase" "$stats" "$fallback" "$status" >>"$SUMMARY_CSV"
}

assert_stats() {
  local stats="$1"
  local expected="$2"
  local max_limit="${3:-}"
  python3 - "$stats" "$expected" "$max_limit" <<'PY'
import sys
fields = sys.argv[1].split(',')
correct, expected, errors = map(int, fields[:3])
if correct != int(sys.argv[2]) or expected != int(sys.argv[2]) or errors != 0:
    raise SystemExit("response correctness gate failed: " + sys.argv[1])
if sys.argv[3] and (fields[6] == "NA" or float(fields[6]) >= float(sys.argv[3])):
    raise SystemExit("latency gate failed: " + sys.argv[1])
PY
}

mkdir -p "$ARTIFACT_DIR"
printf 'scenario,phase,correct,total,transport_errors,p50_ms,p95_ms,p99_ms,max_ms,throughput_rps,db_fallback,status\n' >"$SUMMARY_CSV"
stack status
start_application

mysql_exec "UPDATE tb_shop SET name='${SHOP_NAME}' WHERE id=${SHOP_ID};" >/dev/null
[[ "$(mysql_exec 'SELECT DATABASE();')" == "$SCHEMA" ]] || fail "application schema identity mismatch"
[[ "$(redis_cli --raw GET "m5b:sentinel:${M5B_RUN_ID}")" == "$M5B_RUN_ID" ]] ||
  fail "application Redis sentinel mismatch"

# Keep JVM/class-loading startup noise out of the formal C1 rounds.
warmup_stats="$(run_batch warmup 1000 20 "${BASE_URL}/shop/${SHOP_ID}" "shop:${SHOP_ID}:${SHOP_NAME}")"
assert_stats "$warmup_stats" 1000
redis_cli DEL "cache:shop:${SHOP_ID}" >/dev/null

snapshot_dependencies before

for round in 1 2 3; do
  redis_cli DEL "cache:shop:${SHOP_ID}" >/dev/null
  before="$(cache_count shop_detail db_success)"
  stats="$(run_batch "c1-r${round}-cold" 1000 20 "${BASE_URL}/shop/${SHOP_ID}" "shop:${SHOP_ID}:${SHOP_NAME}")"
  after="$(cache_count shop_detail db_success)"
  fallback=$((after - before))
  assert_stats "$stats" 1000
  [[ "$fallback" -le 1 ]] || fail "C1 cold round ${round} DB fallback exceeded one: ${fallback}"
  record C1 "round-${round}-cold" "$stats" "$fallback" PASS

  before="$(cache_count shop_detail db_success)"
  stats="$(run_batch "c1-r${round}-warm" 1000 20 "${BASE_URL}/shop/${SHOP_ID}" "shop:${SHOP_ID}:${SHOP_NAME}")"
  after="$(cache_count shop_detail db_success)"
  fallback=$((after - before))
  assert_stats "$stats" 1000
  [[ "$fallback" -eq 0 ]] || fail "C1 warm round ${round} used DB fallback: ${fallback}"
  record C1 "round-${round}-warm" "$stats" "$fallback" PASS
done

redis_cli DEL "cache:shop:${MISSING_SHOP_ID}" >/dev/null
before="$(cache_count shop_detail db_empty)"
stats="$(run_batch c2-cold 20 20 "${BASE_URL}/shop/${MISSING_SHOP_ID}" missing)"
after="$(cache_count shop_detail db_empty)"
fallback=$((after - before))
assert_stats "$stats" 20
[[ "$fallback" -le 1 ]] || fail "C2 cold DB fallback exceeded one"
record C2 cold "$stats" "$fallback" PASS
before="$(cache_count shop_detail db_empty)"
stats="$(run_batch c2-warm 100 20 "${BASE_URL}/shop/${MISSING_SHOP_ID}" missing)"
after="$(cache_count shop_detail db_empty)"
fallback=$((after - before))
assert_stats "$stats" 100
[[ "$fallback" -eq 0 ]] || fail "C2 warm used DB fallback"
ttl="$(redis_cli PTTL "cache:shop:${MISSING_SHOP_ID}")"
[[ "$ttl" -gt 0 && "$ttl" -le 30000 ]] || fail "C2 empty TTL is outside bounds: ${ttl}"
record C2 warm "$stats" "$fallback" PASS

redis_cli DEL 'cache:shop:type:list:' >/dev/null
before="$(cache_count shop_type db_success)"
stats="$(run_batch c3-cold 20 20 "${BASE_URL}/shop-type/list" types)"
after="$(cache_count shop_type db_success)"
fallback=$((after - before))
assert_stats "$stats" 20
[[ "$fallback" -le 1 ]] || fail "C3 cold DB fallback exceeded one"
record C3 cold "$stats" "$fallback" PASS
before="$(cache_count shop_type db_success)"
stats="$(run_batch c3-warm 100 20 "${BASE_URL}/shop-type/list" types)"
after="$(cache_count shop_type db_success)"
fallback=$((after - before))
assert_stats "$stats" 100
[[ "$fallback" -eq 0 ]] || fail "C3 warm used DB fallback"
record C3 warm "$stats" "$fallback" PASS

mysql_exec "DROP TABLE IF EXISTS m5b_shop_type_backup; CREATE TABLE m5b_shop_type_backup LIKE tb_shop_type; INSERT INTO m5b_shop_type_backup SELECT * FROM tb_shop_type; DELETE FROM tb_shop_type;" >/dev/null
TYPE_BACKUP_ACTIVE="true"
redis_cli DEL 'cache:shop:type:list:' >/dev/null
before="$(cache_count shop_type db_empty)"
stats="$(run_batch c3-empty 1 1 "${BASE_URL}/shop-type/list" empty_types)"
assert_stats "$stats" 1
after="$(cache_count shop_type db_empty)"
fallback=$((after - before))
ttl="$(redis_cli PTTL 'cache:shop:type:list:')"
[[ "$ttl" -gt 0 && "$ttl" -le 30000 ]] || fail "C3 empty list TTL is outside bounds: ${ttl}"
record C3 empty "$stats" "$fallback" PASS
restore_type_fixture
redis_cli DEL 'cache:shop:type:list:' >/dev/null

redis_cli SET "cache:shop:${SHOP_ID}" '{bad-json' EX 60 >/dev/null
before="$(cache_count shop_detail bad_value)"
stats="$(run_batch c4-malformed 2 1 "${BASE_URL}/shop/${SHOP_ID}" "shop:${SHOP_ID}:${SHOP_NAME}")"
after="$(cache_count shop_detail bad_value)"
assert_stats "$stats" 2
[[ $((after - before)) -eq 1 ]] || fail "C4 malformed did not record one bad value"
record C4 malformed "$stats" 1 PASS

redis_cli SET "cache:shop:${SHOP_ID}" '{"id":999,"name":"wrong"}' EX 60 >/dev/null
before="$(cache_count shop_detail bad_value)"
stats="$(run_batch c4-wrong-id 2 1 "${BASE_URL}/shop/${SHOP_ID}" "shop:${SHOP_ID}:${SHOP_NAME}")"
after="$(cache_count shop_detail bad_value)"
assert_stats "$stats" 2
[[ $((after - before)) -eq 1 ]] || fail "C4 wrong id did not record one bad value"
record C4 wrong-id "$stats" 1 PASS

redis_cli SET 'cache:shop:type:list:' '[{"id":0,"name":"bad"}]' EX 60 >/dev/null
before="$(cache_count shop_type bad_value)"
stats="$(run_batch c4-invalid-list 2 1 "${BASE_URL}/shop-type/list" types)"
after="$(cache_count shop_type bad_value)"
assert_stats "$stats" 2
[[ $((after - before)) -eq 1 ]] || fail "C4 invalid list did not record one bad value"
record C4 invalid-list "$stats" 1 PASS

curl --fail --silent "${BASE_URL}/shop/${SHOP_ID}" >/dev/null
before="$(cache_count shop_detail db_success)"
stack redis-pause
REDIS_RECOVERY="unpause"
stats="$(run_batch f1a-pause 20 20 "${BASE_URL}/shop/${SHOP_ID}" "shop:${SHOP_ID}:${SHOP_NAME}")"
assert_stats "$stats" 20 2000
after="$(cache_count shop_detail db_success)"
record F1a redis-pause "$stats" "$((after - before))" PASS
stack redis-unpause
REDIS_RECOVERY="none"

before="$(cache_count shop_detail db_success)"
stack redis-stop
REDIS_RECOVERY="start"
stats="$(run_batch f1b-stop 20 20 "${BASE_URL}/shop/${SHOP_ID}" "shop:${SHOP_ID}:${SHOP_NAME}")"
assert_stats "$stats" 20 2000
after="$(cache_count shop_detail db_success)"
record F1b redis-stop "$stats" "$((after - before))" PASS
stack redis-start
REDIS_RECOVERY="none"
recovery_started="$(date +%s%N)"
redis_cli DEL "cache:shop:${SHOP_ID}" >/dev/null
curl --fail --silent "${BASE_URL}/shop/${SHOP_ID}" >/dev/null
curl --fail --silent "${BASE_URL}/shop/${SHOP_ID}" >/dev/null
recovery_elapsed_ms=$((($(date +%s%N) - recovery_started) / 1000000))
[[ "$recovery_elapsed_ms" -lt 5000 ]] || fail "F1b cache recovery exceeded five seconds"
printf 'scenario,recovery_ms,status\nF1b,%s,PASS\n' "$recovery_elapsed_ms" \
  >"${ARTIFACT_DIR}/recovery-summary.csv"

curl --fail --silent "${BASE_URL}/shop/${SHOP_ID}" >/dev/null
curl --fail --silent "${BASE_URL}/shop-type/list" >/dev/null
[[ "$(redis_cli TTL "cache:shop:${SHOP_ID}")" -gt 10 ]] || fail "F2 detail TTL is too short"
[[ "$(redis_cli TTL 'cache:shop:type:list:')" -gt 10 ]] || fail "F2 type TTL is too short"
stack mysql-stop
MYSQL_STOPPED="true"
before="$(cache_count shop_detail db_success)"
stats="$(run_batch f2a-shop 20 20 "${BASE_URL}/shop/${SHOP_ID}" "shop:${SHOP_ID}:${SHOP_NAME}")"
assert_stats "$stats" 20
after="$(cache_count shop_detail db_success)"
fallback=$((after - before))
[[ "$fallback" -eq 0 ]] || fail "F2a warm detail unexpectedly used DB"
record F2a warm-shop "$stats" "$fallback" PASS
before="$(cache_count shop_type db_success)"
stats="$(run_batch f2a-types 20 20 "${BASE_URL}/shop-type/list" types)"
assert_stats "$stats" 20
after="$(cache_count shop_type db_success)"
fallback=$((after - before))
[[ "$fallback" -eq 0 ]] || fail "F2a warm types unexpectedly used DB"
record F2a warm-types "$stats" "$fallback" PASS
stack mysql-start
MYSQL_STOPPED="false"

stack redis-pause
REDIS_RECOVERY="unpause"
stats="$(run_batch r1-hot-rank 1 1 "${BASE_URL}/blog/hot?current=1" hot)"
assert_stats "$stats" 1 2000
record R1 redis-pause "$stats" NA PASS
stack redis-unpause
REDIS_RECOVERY="none"

snapshot_dependencies after
python3 - "$ARTIFACT_DIR" <<'PY'
import pathlib, sys
root = pathlib.Path(sys.argv[1])
def value(path, separator):
    text = path.read_text().strip()
    return int(text.split(separator)[-1])
mysql_before = value(root / "before-mysql-queries.txt", "\t")
mysql_after = value(root / "after-mysql-queries.txt", "\t")
redis_before = value(root / "before-redis-commands.txt", ":")
redis_after = value(root / "after-redis-commands.txt", ":")
with (root / "dependency-deltas.csv").open("w") as output:
    output.write("dependency,counter_before,counter_after,delta,status\n")
    for name, before, after in (("mysql_queries", mysql_before, mysql_after),
                                ("redis_commands", redis_before, redis_after)):
        if after < before:
            output.write(f"{name},{before},{after},NA,counter_reset_by_fault_scenario\n")
        else:
            output.write(f"{name},{before},{after},{after-before},comparable\n")
PY
echo "M5B cache scenarios passed. Summary: ${SUMMARY_CSV}"

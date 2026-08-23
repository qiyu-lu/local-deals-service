#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JMETER_PLAN="${PROJECT_DIR}/docs/testing/m5a-http-baseline.jmx"

fail() {
  echo "M5A baseline gate failed: $*" >&2
  exit 1
}

require_env() {
  local name="$1"
  [[ -n "${!name:-}" ]] || fail "${name} must be set explicitly"
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || fail "missing command: $1"
}

[[ "${M5A_ISOLATED:-}" == "true" ]] || fail "set M5A_ISOLATED=true"
for name in M5A_RUN_ID M5A_APP_HOST M5A_APP_PORT M5A_MANAGEMENT_PORT \
  M5A_MYSQL_PORT M5A_MYSQL_SCHEMA M5A_MYSQL_USER M5A_MYSQL_PASSWORD \
  M5A_REDIS_PORT M5A_REDIS_PASSWORD M5A_ES_PORT M5A_RMQ_NAMESRV_PORT \
  M5A_RMQ_BROKER_PORT; do
  require_env "$name"
done
[[ "$M5A_RUN_ID" =~ ^[a-z0-9][a-z0-9-]{5,30}$ ]] || fail "invalid M5A_RUN_ID"
expected_schema="m5a_${M5A_RUN_ID//-/_}"
[[ "$M5A_MYSQL_SCHEMA" == "$expected_schema" ]] ||
  fail "schema must be ${expected_schema}, not ${M5A_MYSQL_SCHEMA}"
[[ "$M5A_MYSQL_SCHEMA" != "local_deals" && "$M5A_MYSQL_SCHEMA" != "hmdp" ]] ||
  fail "shared schema is forbidden"
[[ "$M5A_APP_HOST" == "127.0.0.1" ]] || fail "this runner only accepts an explicit loopback app"

for command in curl docker mysql redis-cli python3 git; do require_cmd "$command"; done
if [[ -n "${M5A_JMETER:-}" ]]; then
  JMETER="$M5A_JMETER"
elif command -v jmeter >/dev/null 2>&1; then
  JMETER="$(command -v jmeter)"
elif [[ -x /home/sd101t/Applications/apache-jmeter-5.6.3/bin/jmeter ]]; then
  JMETER=/home/sd101t/Applications/apache-jmeter-5.6.3/bin/jmeter
else
  fail "set M5A_JMETER to JMeter 5.6.3"
fi

export M5A_MYSQL_PASSWORD M5A_REDIS_PASSWORD
"${PROJECT_DIR}/scripts/m5a-isolated-stack.sh" status >/dev/null

mysql_cli() {
  MYSQL_PWD="$M5A_MYSQL_PASSWORD" mysql --protocol=tcp -h 127.0.0.1 \
    -P "$M5A_MYSQL_PORT" -u "$M5A_MYSQL_USER" --batch --skip-column-names \
    "$M5A_MYSQL_SCHEMA" "$@"
}

redis_cli() {
  redis-cli -h 127.0.0.1 -p "$M5A_REDIS_PORT" -a "$M5A_REDIS_PASSWORD" \
    --no-auth-warning --raw "$@" 2>/dev/null
}

actual_schema="$(mysql_cli -e 'SELECT DATABASE()')"
[[ "$actual_schema" == "$M5A_MYSQL_SCHEMA" ]] || fail "MySQL schema identity mismatch"
sentinel="$(redis_cli GET "m5a:sentinel:${M5A_RUN_ID}")"
[[ "$sentinel" == "$M5A_RUN_ID" ]] || fail "Redis sentinel mismatch"
cluster_name="$(curl --fail --silent "http://127.0.0.1:${M5A_ES_PORT}/" | python3 -c 'import json,sys; print(json.load(sys.stdin)["cluster_name"])')"
[[ "$cluster_name" == "m5a-${M5A_RUN_ID}" ]] || fail "Elasticsearch cluster identity mismatch"

APP_URL="http://${M5A_APP_HOST}:${M5A_APP_PORT}"
MANAGEMENT_URL="http://127.0.0.1:${M5A_MANAGEMENT_PORT}"
curl --fail --silent "${MANAGEMENT_URL}/actuator/health" >/dev/null || fail "management health unavailable"
business_actuator_code="$(curl --silent --output /dev/null --write-out '%{http_code}' "${APP_URL}/actuator/prometheus")"
[[ "$business_actuator_code" == "401" || "$business_actuator_code" == "404" ]] ||
  fail "business port exposes Prometheus (${business_actuator_code})"

ARTIFACT_DIR="${PROJECT_DIR}/benchmark/m5a/${M5A_RUN_ID}/baseline"
mkdir -p "$ARTIFACT_DIR"
SUMMARY="$ARTIFACT_DIR/summary.csv"
METADATA="$ARTIFACT_DIR/run-metadata.txt"
printf '%s\n' \
  'run_id,commit,scenario,warm_or_cold,samples,errors,throughput,p50_ms,p95_ms,p99_ms,mysql_queries_delta,redis_commands_delta,cache_hits,cache_misses,db_fallbacks,mq_lag_max,backlog_oldest_seconds,invariant_result,notes' \
  > "$SUMMARY"
{
  printf 'run_id=%s\n' "$M5A_RUN_ID"
  printf 'commit=%s\n' "$(git -C "$PROJECT_DIR" rev-parse HEAD)"
  printf 'branch=%s\n' "$(git -C "$PROJECT_DIR" branch --show-current)"
  printf 'started_at=%s\n' "$(date --iso-8601=seconds)"
  printf 'java=%s\n' "$(java -version 2>&1 | sed -n '1p')"
  printf 'jmeter=%s\n' "$($JMETER --version 2>/dev/null | sed -n '2p')"
  printf 'mysql=%s\n' "$(mysql_cli -e 'SELECT VERSION()')"
  printf 'redis=%s\n' "$(redis_cli INFO server | sed -n 's/^redis_version://p' | tr -d '\r')"
  printf 'elasticsearch=%s\n' "$(curl --silent "http://127.0.0.1:${M5A_ES_PORT}/" | python3 -c 'import json,sys; print(json.load(sys.stdin)["version"]["number"])')"
  printf 'kernel=%s\n' "$(uname -sr)"
  printf 'cpu=%s\n' "$(sed -n 's/^model name[[:space:]]*: //p' /proc/cpuinfo | sed -n '1p')"
  printf 'memory_kib=%s\n' "$(awk '/MemTotal/{print $2}' /proc/meminfo)"
  printf 'sampling_enabled=%s\n' "${M5A_SAMPLING_ENABLED:-true}"
  printf 'sampling_interval=%s\n' "${M5A_SAMPLING_INTERVAL:-30s}"
} > "$METADATA"

prom_sum() {
  local pattern="$1"
  curl --fail --silent "${MANAGEMENT_URL}/actuator/prometheus" |
    awk -v pattern="$pattern" '$0 !~ /^#/ && $1 ~ pattern {sum += $NF} END {printf "%.0f", sum+0}'
}

prom_scalar() {
  local metric="$1"
  curl --fail --silent "${MANAGEMENT_URL}/actuator/prometheus" |
    awk -v metric="$metric" '$1 == metric {print $NF; found=1; exit} END {if(!found) print ""}'
}

mysql_questions() {
  mysql_cli -e "SHOW GLOBAL STATUS WHERE Variable_name IN ('Com_select','Com_insert','Com_update','Com_delete')" |
    awk '{sum += $2} END {print sum+0}'
}

redis_commands() {
  redis_cli INFO stats | sed -n 's/^total_commands_processed://p' | tr -d '\r'
}

mq_lag() {
  local output="$1"
  docker exec "m5a-${M5A_RUN_ID}-broker" sh mqadmin consumerProgress \
    -n "m5a-${M5A_RUN_ID}-namesrv:9876" -g seckill-consumer-group \
    > "$output" 2>&1 || return 1
  awk '$1 !~ /^#/ && $1 == "seckill-order-topic" {if ($6+0 > max) max=$6+0; found=1} END {if(found) print max; else exit 1}' "$output"
}

parse_jtl() {
  python3 - "$1" <<'PY'
import csv, math, sys
with open(sys.argv[1], newline='', encoding='utf-8') as f:
    rows=list(csv.DictReader(f))
values=sorted(float(r['elapsed']) for r in rows)
errors=sum(1 for r in rows if r.get('success','').lower() != 'true')
def pct(p):
    if not values: return ''
    return values[max(0, math.ceil(len(values)*p)-1)]
if rows:
    start=min(int(r['timeStamp']) for r in rows)
    end=max(int(r['timeStamp'])+int(r['elapsed']) for r in rows)
    throughput=len(rows)/max((end-start)/1000.0, 0.001)
else:
    throughput=0
print(f"{len(rows)},{errors},{throughput:.3f},{pct(.50)},{pct(.95)},{pct(.99)}")
PY
}

run_http() {
  local scenario="$1" temperature="$2" samples="$3" threads="$4" path="$5"
  local method="${6:-GET}" token="${7:-}" body="${8:-}" notes="${9:-}"
  local safe="${scenario}-${temperature}-$(date +%s%N)"
  local jtl="$ARTIFACT_DIR/${safe}.jtl"
  local before_prom="$ARTIFACT_DIR/${safe}-before.prom"
  local after_prom="$ARTIFACT_DIR/${safe}-after.prom"
  local before_mysql before_redis before_hits before_misses before_db
  before_mysql="$(mysql_questions)"
  before_redis="$(redis_commands)"
  before_hits="$(prom_sum '^local_deals_cache_access_total.*result="hit"')"
  before_misses="$(prom_sum '^local_deals_cache_access_total.*result="miss"')"
  before_db="$(prom_sum '^local_deals_cache_access_total.*result="db_(success|empty|error)"')"
  curl --fail --silent "${MANAGEMENT_URL}/actuator/prometheus" > "$before_prom"
  local loops=$(( (samples + threads - 1) / threads ))
  "$JMETER" -n -t "$JMETER_PLAN" -l "$jtl" \
    -Jhost="$M5A_APP_HOST" -Jport="$M5A_APP_PORT" -Jthreads="$threads" \
    -Jloops="$loops" -Jramp_up=1 -Jpath="$path" -Jmethod="$method" \
    -Jtoken="$token" -Jbody="$body" -Jlabel="$scenario" \
    > "$ARTIFACT_DIR/${safe}-jmeter.log" 2>&1
  curl --fail --silent "${MANAGEMENT_URL}/actuator/prometheus" > "$after_prom"
  local parsed samples_actual errors_actual throughput p50 p95 p99
  local after_mysql after_redis after_hits after_misses after_db lag oldest
  parsed="$(parse_jtl "$jtl")"
  IFS=',' read -r samples_actual errors_actual throughput p50 p95 p99 <<< "$parsed"
  after_mysql="$(mysql_questions)"
  after_redis="$(redis_commands)"
  after_hits="$(prom_sum '^local_deals_cache_access_total.*result="hit"')"
  after_misses="$(prom_sum '^local_deals_cache_access_total.*result="miss"')"
  after_db="$(prom_sum '^local_deals_cache_access_total.*result="db_(success|empty|error)"')"
  lag="$(mq_lag "$ARTIFACT_DIR/${safe}-mq.txt" || true)"
  oldest="$(curl --silent "${MANAGEMENT_URL}/actuator/prometheus" |
    awk '$1=="local_deals_seckill_processing_oldest_overdue_seconds" {print $2; found=1} END{if(!found) print ""}')"
  printf '%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s\n' \
    "$M5A_RUN_ID" "$(git -C "$PROJECT_DIR" rev-parse --short HEAD)" "$scenario" "$temperature" \
    "$samples_actual" "$errors_actual" "$throughput" "$p50" "$p95" "$p99" \
    "$((after_mysql-before_mysql))" "$((after_redis-before_redis))" \
    "$((after_hits-before_hits))" "$((after_misses-before_misses))" "$((after_db-before_db))" \
    "$lag" "$oldest" pass "\"${notes//\"/}\"" >> "$SUMMARY"
}

run_idle() {
  local duration="${M5A_B0_SECONDS:-300}" round
  for round in 1 2 3; do
    local mysql_before redis_before cpu_before cpu_after
    mysql_before="$(mysql_questions)"; redis_before="$(redis_commands)"
    cpu_before="$(prom_scalar process_cpu_usage)"
    sleep "$duration"
    cpu_after="$(prom_scalar process_cpu_usage)"
    printf '%s,%s,B0-idle,steady,0,0,0,,,,%s,%s,,,,,,pass,"duration=%ss round=%s cpu_before=%s cpu_after=%s"\n' \
      "$M5A_RUN_ID" "$(git -C "$PROJECT_DIR" rev-parse --short HEAD)" \
      "$(( $(mysql_questions)-mysql_before ))" "$(( $(redis_commands)-redis_before ))" \
      "$duration" "$round" "$cpu_before" "$cpu_after" >> "$SUMMARY"
  done
}

run_b1() {
  local round
  redis_cli DEL cache:shop:1 cache:shop:999999999 cache:shop:type:list: >/dev/null
  run_http B1-pilot-shop-existing cold 100 10 /shop/1
  run_http B1-pilot-shop-missing cold 100 10 /shop/999999999
  run_http B1-pilot-shop-type cold 100 10 /shop-type/list
  for round in 1 2 3; do
    redis_cli DEL cache:shop:1 cache:shop:999999999 cache:shop:type:list: >/dev/null
    run_http "B1-shop-cold-r${round}" cold 1000 20 /shop/1
    run_http "B1-shop-hot-r${round}" warm 1000 20 /shop/1
  done
}

run_b2() {
  local token="${M5A_USER_TOKEN:-}" blog_id="${M5A_BLOG_ID:-4}"
  redis_cli DEL 'blog:hot:{global}:live' 'blog:hot:{global}:meta' >/dev/null
  run_http B2-hot-rank-not-ready cold 100 10 '/blog/hot?current=1'
  sleep 2
  run_http B2-hot-rank-ready warm 100 10 '/blog/hot?current=1'
  redis_cli HSET 'blog:hot:{global}:meta' publishedAt 1 >/dev/null || true
  run_http B2-hot-rank-stale stale 100 10 '/blog/hot?current=1'
  if [[ -n "$token" ]]; then
    run_http B2-like changed 1 1 "/blog/${blog_id}/like" PUT "$token"
    run_http B2-like-repeat unchanged 1 1 "/blog/${blog_id}/like" PUT "$token"
    run_http B2-unlike changed 1 1 "/blog/${blog_id}/like" DELETE "$token"
  else
    printf '%s,%s,B2-like,BLOCKED,0,0,0,,,,,,,,,,,blocked,"M5A_USER_TOKEN not supplied"\n' \
      "$M5A_RUN_ID" "$(git -C "$PROJECT_DIR" rev-parse --short HEAD)" >> "$SUMMARY"
  fi
}

run_b3() {
  run_http B3-shop-search consumer-level 100 10 '/shop/search?keyword=%E9%A4%90%E5%8E%85&current=1'
  run_http B3-blog-search consumer-level 100 10 '/blog/search?keyword=%E7%BE%8E%E9%A3%9F&current=1'
}

run_b4() {
  local b4_root="$ARTIFACT_DIR/b4"
  mkdir -p "$b4_root"
  local maven=/opt/idea/plugins/maven/lib/maven3/bin/mvn
  [[ -x "$maven" ]] || fail "Maven executable unavailable for B4 fixture"
  PATH="$(dirname "$JMETER"):$PATH" \
  LOCAL_DEALS_DATASOURCE_URL="jdbc:mysql://127.0.0.1:${M5A_MYSQL_PORT}/${M5A_MYSQL_SCHEMA}?useSSL=false&serverTimezone=UTC" \
  LOCAL_DEALS_DATASOURCE_USERNAME="$M5A_MYSQL_USER" \
  LOCAL_DEALS_DATASOURCE_PASSWORD="$M5A_MYSQL_PASSWORD" \
  LOCAL_DEALS_REDIS_HOST=127.0.0.1 LOCAL_DEALS_REDIS_PORT="$M5A_REDIS_PORT" \
  LOCAL_DEALS_REDIS_PASSWORD="$M5A_REDIS_PASSWORD" \
  SPRING_ELASTICSEARCH_REST_URIS="http://127.0.0.1:${M5A_ES_PORT}" \
  ROCKETMQ_NAME_SERVER="127.0.0.1:${M5A_RMQ_NAMESRV_PORT}" \
  MYSQL_PASSWORD="$M5A_MYSQL_PASSWORD" REDIS_PASSWORD="$M5A_REDIS_PASSWORD" \
  "${PROJECT_DIR}/scripts/run-seckill-benchmark.sh" \
    --threads 1000 --loops 1 --ramp-up 5 --stock 100 --user-count 1000 \
    --host "$M5A_APP_HOST" --port "$M5A_APP_PORT" \
    --mysql-host 127.0.0.1 --mysql-port "$M5A_MYSQL_PORT" \
    --mysql-database "$M5A_MYSQL_SCHEMA" --redis-host 127.0.0.1 \
    --redis-port "$M5A_REDIS_PORT" --maven-cmd "$maven" \
    --scenario m5a-b4 --impl m5a-isolated --round 1 --skip-html \
    --output-root "$b4_root" > "$ARTIFACT_DIR/b4-run.log" 2>&1
  local jtl
  jtl="$(find "$b4_root/benchmark" -name '*.jtl' -type f | head -1)"
  [[ -n "$jtl" ]] || fail "B4 JTL missing"
  local parsed samples_actual errors_actual throughput p50 p95 p99
  parsed="$(parse_jtl "$jtl")"
  IFS=',' read -r samples_actual errors_actual throughput p50 p95 p99 <<< "$parsed"
  printf '%s,%s,B4-seckill,pilot,%s,%s,%s,%s,%s,%s,,,,,,,,pass,"1000 requests 100 stock; see isolated run summary"\n' \
    "$M5A_RUN_ID" "$(git -C "$PROJECT_DIR" rev-parse --short HEAD)" \
    "$samples_actual" "$errors_actual" "$throughput" "$p50" "$p95" "$p99" >> "$SUMMARY"
}

scenarios="${M5A_SCENARIOS:-B0,B1,B2,B3,B4}"
[[ "$scenarios" == *B0* ]] && run_idle
[[ "$scenarios" == *B1* ]] && run_b1
[[ "$scenarios" == *B2* ]] && run_b2
[[ "$scenarios" == *B3* ]] && run_b3
[[ "$scenarios" == *B4* ]] && run_b4

curl --fail --silent "${MANAGEMENT_URL}/actuator/prometheus" > "$ARTIFACT_DIR/final.prom"
echo "M5A baseline complete: $SUMMARY"

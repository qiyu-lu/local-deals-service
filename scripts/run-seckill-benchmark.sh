#!/usr/bin/env bash
set -euo pipefail

SCRIPT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROJECT_DIR="$SCRIPT_ROOT"
OUTPUT_ROOT="$SCRIPT_ROOT"

DATE="$(date +%F)"
MODULE="seckill"
IMPL="rocketmq-reservation-v2"
SCENARIO="seckill-rocketmq-v2"
THREADS=100
LOOPS=1
RAMP_UP=5
STOCK=100
USER_COUNT=1000
EXPECTED_ORDERS=""
VOUCHER_ID=""
ROUND=""
HOST="localhost"
PORT=8083
TOKENS_FILE="benchmark/tokens.csv"
JMETER_PLAN="docs/Summary Report.jmx"
POLL_INTERVAL_MS=50
DRAIN_TIMEOUT_MS=30000
MYSQL_HOST="${MYSQL_HOST:-${LOCAL_DEALS_MYSQL_HOST:-localhost}}"
MYSQL_PORT="${MYSQL_PORT:-${LOCAL_DEALS_MYSQL_PORT:-3306}}"
REDIS_HOST="${REDIS_HOST:-${LOCAL_DEALS_REDIS_HOST:-localhost}}"
REDIS_PORT="${REDIS_PORT:-${LOCAL_DEALS_REDIS_PORT:-6379}}"
MYSQL_USER="${MYSQL_USER:-${LOCAL_DEALS_DATASOURCE_USERNAME:-root}}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-${LOCAL_DEALS_DATASOURCE_PASSWORD:-}}"
MYSQL_DATABASE="${MYSQL_DATABASE:-local_deals}"
REDIS_PASSWORD="${REDIS_PASSWORD:-${LOCAL_DEALS_REDIS_PASSWORD:-}}"
MANAGEMENT_HOST="${MANAGEMENT_HOST:-${M7RC_MANAGEMENT_HOST:-}}"
MANAGEMENT_PORT="${MANAGEMENT_PORT:-${M7RC_MANAGEMENT_PORT:-}}"
M7RC_RUN_ID="${M7RC_RUN_ID:-}"
M7RC_PROJECT="${M7RC_PROJECT:-}"
M7RC_RMQ_NAMESRV="${M7RC_RMQ_NAMESRV:-}"
M7RC_RMQ_CLIENT_NAMESRV="${M7RC_RMQ_CLIENT_NAMESRV:-}"
M7RC_RMQ_NAMESRV_PORT="${M7RC_RMQ_NAMESRV_PORT:-}"
M7RC_RMQ_GROUP="${M7RC_RMQ_GROUP:-seckill-consumer-group}"
M7RC_RMQ_TOPIC="${M7RC_RMQ_TOPIC:-seckill-order-topic}"
M7RC_BROKER_CONTAINER="${M7RC_BROKER_CONTAINER:-}"
JAVA_HOME="${JAVA_HOME:-/home/sd101t/.jdks/dragonwell-ex-1.8.0_472}"
MAVEN_CMD="${MAVEN_CMD:-}"
SKIP_PREPARE=0
SKIP_RESET=0
SKIP_HTML=0

usage() {
  cat <<'EOF'
Usage:
  scripts/run-seckill-benchmark.sh [options]

Options:
  --threads N             JMeter thread count. Default: 100
  --loops N               JMeter loop count. Default: 1
  --ramp-up N             JMeter ramp-up seconds. Default: 5
  --stock N               Benchmark seckill stock. Default: 100
  --user-count N          Generated benchmark user/token count. Default: 1000
  --expected-orders N     Expected final MySQL order count. Default: min(stock, threads * loops, user-count)
  --voucher-id N          Existing seckill voucher id. If omitted, BenchmarkDataTool resolves one.
  --round N               Same-scenario run number. Default: next available round for the same day/scenario.
  --host HOST             Target host for JMeter. Default: localhost
  --port PORT             Target port for JMeter. Default: 8083
  --tokens-file PATH      Token CSV path. Default: benchmark/tokens.csv
  --jmeter-plan PATH      JMeter plan path. Default: docs/Summary Report.jmx in project-dir.
  --scenario NAME         Output folder under docs/JmeterTestSummary. Default: seckill-rocketmq-v2
  --impl NAME             File-name implementation label. Default: rocketmq-reservation-v2
  --project-dir PATH      Project checkout to run Maven/JMeter from. Default: this repo.
  --output-root PATH      Repo root where benchmark artifacts are written. Default: this repo.
  --mysql-host HOST       MySQL host. Default: localhost (override via MYSQL_HOST or LOCAL_DEALS_MYSQL_HOST in .env)
  --mysql-port PORT       MySQL port. Default: 3306
  --redis-host HOST       Redis host. Default: localhost (override via REDIS_HOST or LOCAL_DEALS_REDIS_HOST in .env)
  --redis-port PORT       Redis port. Default: 6379
  --mysql-database NAME   MySQL database name. Default: local_deals
  --management-host HOST  Management endpoint host for Prometheus deltas.
  --management-port PORT  Management endpoint port for Prometheus deltas.
  --run-id ID             Dedicated M7-RC run-id for RocketMQ lag evidence.
  --rmq-namesrv HOST:PORT Dedicated RocketMQ NameServer for lag evidence.
  --rmq-client-namesrv HOST:PORT NameServer address used by Maven fixture helpers.
  --rmq-group GROUP       RocketMQ seckill consumer group. Default: seckill-consumer-group.
  --rmq-topic TOPIC       RocketMQ seckill main topic. Default: seckill-order-topic.
  --broker-container NAME Dedicated broker container for mqadmin lag evidence.
  --maven-cmd PATH        Maven executable path. Default: mvn, ./mvnw, or IDEA bundled Maven.
  --java-home PATH        JAVA_HOME for Maven benchmark helpers. Default: Dragonwell JDK 8 in ~/.jdks.
  --poll-ms N             MySQL/Redis polling interval after JMeter exits. Default: 50
  --timeout-ms N          Drain wait timeout. Default: 30000
  --skip-prepare          Do not regenerate benchmark users/tokens.
  --skip-reset            Do not reset stock/orders/Redis keys before JMeter.
  --skip-html             Do not generate JMeter HTML dashboard.
  -h, --help              Show this help.

Example:
  scripts/run-seckill-benchmark.sh \
    --threads 5000 \
    --loops 5 \
    --stock 1000 \
    --user-count 5000 \
    --voucher-id 11 \
    --round 1
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --threads) THREADS="$2"; shift 2 ;;
    --loops) LOOPS="$2"; shift 2 ;;
    --ramp-up) RAMP_UP="$2"; shift 2 ;;
    --stock) STOCK="$2"; shift 2 ;;
    --user-count) USER_COUNT="$2"; shift 2 ;;
    --expected-orders) EXPECTED_ORDERS="$2"; shift 2 ;;
    --voucher-id) VOUCHER_ID="$2"; shift 2 ;;
    --round) ROUND="$2"; shift 2 ;;
    --host) HOST="$2"; shift 2 ;;
    --port) PORT="$2"; shift 2 ;;
    --tokens-file) TOKENS_FILE="$2"; shift 2 ;;
    --jmeter-plan) JMETER_PLAN="$2"; shift 2 ;;
    --scenario) SCENARIO="$2"; shift 2 ;;
    --impl) IMPL="$2"; shift 2 ;;
    --project-dir) PROJECT_DIR="$2"; shift 2 ;;
    --output-root) OUTPUT_ROOT="$2"; shift 2 ;;
    --mysql-host) MYSQL_HOST="$2"; shift 2 ;;
    --mysql-port) MYSQL_PORT="$2"; shift 2 ;;
    --redis-host) REDIS_HOST="$2"; shift 2 ;;
    --redis-port) REDIS_PORT="$2"; shift 2 ;;
    --mysql-database) MYSQL_DATABASE="$2"; shift 2 ;;
    --management-host) MANAGEMENT_HOST="$2"; shift 2 ;;
    --management-port) MANAGEMENT_PORT="$2"; shift 2 ;;
    --run-id) M7RC_RUN_ID="$2"; shift 2 ;;
    --rmq-namesrv) M7RC_RMQ_NAMESRV="$2"; shift 2 ;;
    --rmq-client-namesrv) M7RC_RMQ_CLIENT_NAMESRV="$2"; shift 2 ;;
    --rmq-group) M7RC_RMQ_GROUP="$2"; shift 2 ;;
    --rmq-topic) M7RC_RMQ_TOPIC="$2"; shift 2 ;;
    --broker-container) M7RC_BROKER_CONTAINER="$2"; shift 2 ;;
    --maven-cmd) MAVEN_CMD="$2"; shift 2 ;;
    --java-home) JAVA_HOME="$2"; shift 2 ;;
    --poll-ms) POLL_INTERVAL_MS="$2"; shift 2 ;;
    --timeout-ms) DRAIN_TIMEOUT_MS="$2"; shift 2 ;;
    --skip-prepare) SKIP_PREPARE=1; shift ;;
    --skip-reset) SKIP_RESET=1; shift ;;
    --skip-html) SKIP_HTML=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage; exit 1 ;;
  esac
done

PROJECT_DIR="$(cd "$PROJECT_DIR" && pwd)"
mkdir -p "$OUTPUT_ROOT"
OUTPUT_ROOT="$(cd "$OUTPUT_ROOT" && pwd)"
cd "$PROJECT_DIR"

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing required command: $1" >&2
    exit 1
  fi
}

require_cmd jmeter
require_cmd mysql
require_cmd redis-cli
require_cmd python3

require_secret() {
  local name="$1"
  if [[ -z "${!name:-}" ]]; then
    echo "Missing required environment variable: ${name}. Source .env first, or export it before running this script." >&2
    exit 1
  fi
}

require_secret MYSQL_PASSWORD
require_secret REDIS_PASSWORD

resolve_maven_cmd() {
  if [[ -n "$MAVEN_CMD" ]]; then
    printf '%s\n' "$MAVEN_CMD"
    return
  fi
  if [[ -x "./mvnw" ]]; then
    printf '%s\n' "./mvnw"
    return
  fi
  if command -v mvn >/dev/null 2>&1; then
    command -v mvn
    return
  fi
  if [[ -x "/opt/idea/plugins/maven/lib/maven3/bin/mvn" ]]; then
    printf '%s\n' "/opt/idea/plugins/maven/lib/maven3/bin/mvn"
    return
  fi
  echo "Missing required Maven command. Set --maven-cmd /path/to/mvn or MAVEN_CMD=/path/to/mvn." >&2
  exit 1
}

MAVEN_CMD="$(resolve_maven_cmd)"
if [[ ! -x "$MAVEN_CMD" && "$MAVEN_CMD" != "./mvnw" ]]; then
  echo "Maven command is not executable: $MAVEN_CMD" >&2
  exit 1
fi
if [[ -n "$JAVA_HOME" && ! -x "$JAVA_HOME/bin/java" ]]; then
  echo "JAVA_HOME does not contain an executable bin/java: $JAVA_HOME" >&2
  exit 1
fi
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

TOTAL_REQUESTS=$((THREADS * LOOPS))
if [[ -z "$EXPECTED_ORDERS" ]]; then
  EXPECTED_ORDERS="$STOCK"
  if (( TOTAL_REQUESTS < EXPECTED_ORDERS )); then
    EXPECTED_ORDERS="$TOTAL_REQUESTS"
  fi
  if (( USER_COUNT < EXPECTED_ORDERS )); then
    EXPECTED_ORDERS="$USER_COUNT"
  fi
fi

RESULT_DIR_REL="docs/JmeterTestSummary/${SCENARIO}"
BENCHMARK_DIR_REL="benchmark"
RESULT_DIR="${OUTPUT_ROOT}/${RESULT_DIR_REL}"
BENCHMARK_DIR="${OUTPUT_ROOT}/${BENCHMARK_DIR_REL}"
RUN_ID_PREFIX="${DATE}-${MODULE}-${IMPL}-${THREADS}t-${LOOPS}l"
if [[ -z "$ROUND" ]]; then
  ROUND=1
  while [[ -e "${RESULT_DIR}/${RUN_ID_PREFIX}-summary-r${ROUND}.csv" \
    || -e "${RESULT_DIR}/${RUN_ID_PREFIX}-aggregate-r${ROUND}.csv" \
    || -e "${BENCHMARK_DIR}/${RUN_ID_PREFIX}-r${ROUND}.jtl" ]]; do
    ROUND=$((ROUND + 1))
  done
fi
RUN_ID="${RUN_ID_PREFIX}-r${ROUND}"
SUMMARY_CSV_REL="${RESULT_DIR_REL}/${RUN_ID_PREFIX}-summary-r${ROUND}.csv"
AGGREGATE_CSV_REL="${RESULT_DIR_REL}/${RUN_ID_PREFIX}-aggregate-r${ROUND}.csv"
METRICS_CSV_REL="${RESULT_DIR_REL}/metrics.csv"
JTL_FILE_REL="${BENCHMARK_DIR_REL}/${RUN_ID}.jtl"
HTML_REPORT_DIR_REL="${BENCHMARK_DIR_REL}/report-${RUN_ID}"
RUN_SUMMARY_REL="${RESULT_DIR_REL}/${RUN_ID}-run-summary.md"
SUMMARY_CSV="${OUTPUT_ROOT}/${SUMMARY_CSV_REL}"
AGGREGATE_CSV="${OUTPUT_ROOT}/${AGGREGATE_CSV_REL}"
METRICS_CSV="${OUTPUT_ROOT}/${METRICS_CSV_REL}"
JTL_FILE="${OUTPUT_ROOT}/${JTL_FILE_REL}"
HTML_REPORT_DIR="${OUTPUT_ROOT}/${HTML_REPORT_DIR_REL}"
RUN_SUMMARY="${OUTPUT_ROOT}/${RUN_SUMMARY_REL}"
if [[ "$TOKENS_FILE" = /* ]]; then
  TOKENS_FILE_ABS="$TOKENS_FILE"
else
  TOKENS_FILE_ABS="${PROJECT_DIR}/${TOKENS_FILE}"
fi
if [[ "$JMETER_PLAN" = /* ]]; then
  JMETER_PLAN_ABS="$JMETER_PLAN"
else
  JMETER_PLAN_ABS="${PROJECT_DIR}/${JMETER_PLAN}"
fi

mkdir -p "$RESULT_DIR" "$BENCHMARK_DIR"

PROMETHEUS_URL=""
PROMETHEUS_BEFORE="${BENCHMARK_DIR}/${RUN_ID}-prometheus-before.txt"
PROMETHEUS_AFTER="${BENCHMARK_DIR}/${RUN_ID}-prometheus-after.txt"
MQ_PROGRESS_FILE="${BENCHMARK_DIR}/${RUN_ID}-consumer-progress-final.txt"
MQ_MAIN_LAG="NA"
MQ_RETRY_LAG="NA"
MQ_DLQ_LAG="NA"
if [[ -n "$MANAGEMENT_HOST" && -n "$MANAGEMENT_PORT" ]]; then
  PROMETHEUS_URL="http://${MANAGEMENT_HOST}:${MANAGEMENT_PORT}/actuator/prometheus"
fi
if [[ -z "$M7RC_RMQ_NAMESRV" && -n "$M7RC_RUN_ID" ]]; then
  M7RC_RMQ_NAMESRV="${M7RC_RUN_ID}-namesrv:9876"
fi
if [[ -z "$M7RC_RMQ_CLIENT_NAMESRV" && -n "$M7RC_RMQ_NAMESRV_PORT" ]]; then
  M7RC_RMQ_CLIENT_NAMESRV="127.0.0.1:${M7RC_RMQ_NAMESRV_PORT}"
fi
if [[ -z "$M7RC_BROKER_CONTAINER" && -n "$M7RC_RUN_ID" ]]; then
  M7RC_BROKER_CONTAINER="${M7RC_RUN_ID}-broker"
fi
MAVEN_ISOLATION_ARGS=()
if [[ -n "$M7RC_RMQ_CLIENT_NAMESRV" ]]; then
  MAVEN_ISOLATION_ARGS=(
    "-Drocketmq.name-server=${M7RC_RMQ_CLIENT_NAMESRV}"
    "-Dspring.elasticsearch.rest.uris=http://127.0.0.1:${M7RC_ES_PORT:-9200}"
    "-Dlocal-deals.seckill.topic=${M7RC_RMQ_TOPIC}"
    "-Dlocal-deals.seckill.consumer-group=${M7RC_RMQ_GROUP}"
  )
fi

capture_prometheus() {
  local phase="$1" output="$2"
  if [[ -z "$PROMETHEUS_URL" ]]; then
    : >"$output"
    return 0
  fi
  if ! curl --fail --silent --show-error "$PROMETHEUS_URL" >"$output"; then
    echo "Prometheus snapshot failed for phase=${phase}; counters will be NA." >&2
    : >"$output"
  fi
}

prometheus_value() {
  local file="$1" metric="$2" label_selector="$3"
  python3 - "$file" "$metric" "$label_selector" <<'PY'
import re
import sys

path, wanted_metric, selector = sys.argv[1:]
wanted = {}
for item in selector.split(','):
    if item:
        key, value = item.split('=', 1)
        wanted[key] = value

total = 0.0
found = False
line_pattern = re.compile(
    r'^([a-zA-Z_:][a-zA-Z0-9_:]*)(?:\{([^}]*)\})?\s+([-+0-9.eE]+)'
)
label_pattern = re.compile(r'([a-zA-Z_][a-zA-Z0-9_]*)="((?:\\.|[^"\\])*)"')
try:
    lines = open(path, encoding='utf-8')
except OSError:
    print('NA')
    raise SystemExit
with lines:
    for raw in lines:
        match = line_pattern.match(raw.strip())
        if not match or match.group(1) != wanted_metric:
            continue
        labels = dict(label_pattern.findall(match.group(2) or ''))
        if all(labels.get(key) == value for key, value in wanted.items()):
            total += float(match.group(3))
            found = True
print(format(total, '.15g') if found else 'NA')
PY
}

prometheus_delta() {
  local metric="$1" label_selector="$2" before after
  if [[ -z "$PROMETHEUS_URL" ]]; then
    printf 'NA\n'
    return 0
  fi
  before="$(prometheus_value "$PROMETHEUS_BEFORE" "$metric" "$label_selector")"
  after="$(prometheus_value "$PROMETHEUS_AFTER" "$metric" "$label_selector")"
  if [[ "$before" == "NA" || "$after" == "NA" ]]; then
    printf 'NA\n'
    return 0
  fi
  awk -v before="$before" -v after="$after" 'BEGIN { printf "%.15g\n", after - before }'
}

topic_depth() {
  local topic="$1"
  local topics
  topics="$(docker exec "$M7RC_BROKER_CONTAINER" sh mqadmin topicList \
    -n "$M7RC_RMQ_NAMESRV" 2>/dev/null || true)"
  if ! printf '%s\n' "$topics" | grep -Fxq "$topic"; then
    printf '0\n'
    return 0
  fi
  docker exec "$M7RC_BROKER_CONTAINER" sh mqadmin topicStatus \
    -n "$M7RC_RMQ_NAMESRV" -t "$topic" 2>/dev/null | \
    awk '$2 ~ /^[0-9]+$/ && $3 ~ /^[0-9]+$/ && $4 ~ /^[0-9]+$/ { depth += $4 - $3; found = 1 } END { if (found) printf "%d\n", depth + 0; else exit 1 }'
}

capture_rocketmq_lag() {
  if [[ -z "$M7RC_BROKER_CONTAINER" || -z "$M7RC_RMQ_NAMESRV" || -z "$M7RC_RMQ_GROUP" ]]; then
    : >"$MQ_PROGRESS_FILE"
    return 0
  fi
  if ! docker exec "$M7RC_BROKER_CONTAINER" sh mqadmin consumerProgress \
      -n "$M7RC_RMQ_NAMESRV" -g "$M7RC_RMQ_GROUP" >"$MQ_PROGRESS_FILE" 2>&1; then
    echo "RocketMQ consumerProgress failed; final lag will be NA." >&2
    return 0
  fi
  local parsed
  parsed="$(awk -v topic="$M7RC_RMQ_TOPIC" -v retry="%RETRY%${M7RC_RMQ_GROUP}" '
    $1 == topic { main += $6; found = 1 }
    $1 == retry { retried += $6 }
    END { if (found) printf "%d %d\n", main + 0, retried + 0; else exit 1 }
  ' "$MQ_PROGRESS_FILE" 2>/dev/null || true)"
  if [[ "$parsed" =~ ^[0-9]+\ [0-9]+$ ]]; then
    read -r MQ_MAIN_LAG MQ_RETRY_LAG <<<"$parsed"
  fi
  MQ_DLQ_LAG="$(topic_depth "%DLQ%${M7RC_RMQ_GROUP}" 2>/dev/null || printf 'NA')"
}

wait_for_rocketmq_lag_zero() {
  if [[ -z "$M7RC_BROKER_CONTAINER" || -z "$M7RC_RMQ_NAMESRV" || -z "$M7RC_RMQ_GROUP" ]]; then
    return 0
  fi
  local deadline_ms now_ms
  deadline_ms=$(( $(date +%s%3N) + DRAIN_TIMEOUT_MS ))
  while true; do
    capture_rocketmq_lag
    if [[ "$MQ_MAIN_LAG" == "0" && "$MQ_RETRY_LAG" == "0" && "$MQ_DLQ_LAG" == "0" ]]; then
      return 0
    fi
    now_ms="$(date +%s%3N)"
    (( now_ms >= deadline_ms )) && return 0
    sleep 0.2
  done
}

if [[ "$SKIP_PREPARE" -eq 0 ]]; then
  "$MAVEN_CMD" \
    "${MAVEN_ISOLATION_ARGS[@]}" \
    -Dtest=BenchmarkDataTool#prepareBenchmarkUsersAndTokens \
    -Dbench.userCount="$USER_COUNT" \
    -Dbench.tokensFile="$TOKENS_FILE" \
    test
fi

if [[ "$SKIP_RESET" -eq 0 ]]; then
  reset_cmd=(
    "$MAVEN_CMD"
    "${MAVEN_ISOLATION_ARGS[@]}"
    -Dtest=BenchmarkDataTool#resetSeckillBenchmarkData
    -Dbench.stock="$STOCK"
    test
  )
  if [[ -n "$VOUCHER_ID" ]]; then
      reset_cmd=(
        "$MAVEN_CMD"
        "${MAVEN_ISOLATION_ARGS[@]}"
        -Dtest=BenchmarkDataTool#resetSeckillBenchmarkData
      -Dbench.stock="$STOCK"
      -Dbench.voucherId="$VOUCHER_ID"
      test
    )
  fi
  reset_output="$("${reset_cmd[@]}" 2>&1 | tee "${BENCHMARK_DIR}/${RUN_ID}-reset.log")"
  if [[ -z "$VOUCHER_ID" ]]; then
    VOUCHER_ID="$(printf '%s\n' "$reset_output" | sed -n 's/.*voucherId=\([0-9][0-9]*\).*/\1/p' | tail -1)"
  fi
fi

if [[ -z "$VOUCHER_ID" ]]; then
  echo "voucherId was not provided and could not be parsed from reset output." >&2
  exit 1
fi

capture_prometheus before "$PROMETHEUS_BEFORE"

redis_cmd() {
  redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" -a "$REDIS_PASSWORD" --no-auth-warning "$@" 2>/dev/null
}

rm -f "$JTL_FILE" "$SUMMARY_CSV" "$AGGREGATE_CSV"
if [[ "$SKIP_HTML" -eq 0 ]]; then
  rm -rf "$HTML_REPORT_DIR"
fi

jmeter_cmd=(
  jmeter
  -n
  -t "$JMETER_PLAN_ABS"
  -l "$JTL_FILE"
  -Jhost="$HOST"
  -Jport="$PORT"
  -JvoucherId="$VOUCHER_ID"
  -Jthreads="$THREADS"
  -JrampUp="$RAMP_UP"
  -Jloops="$LOOPS"
  -JtokensFile="$TOKENS_FILE_ABS"
  -Jjmeter.save.saveservice.output_format=csv
  -Jjmeter.save.saveservice.print_field_names=true
  -Jjmeter.save.saveservice.timestamp_format=ms
)

jmeter_start_ms="$(date +%s%3N)"
"${jmeter_cmd[@]}"
jmeter_end_ms="$(date +%s%3N)"
jmeter_elapsed_ms=$((jmeter_end_ms - jmeter_start_ms))

mysql_scalar() {
  mysql -N -B -h"$MYSQL_HOST" -P"$MYSQL_PORT" -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE" \
    -e "$1" 2>/dev/null | tail -1
}

redis_scalar() {
  redis_cmd --raw "$@" | sed -n '1p'
}

reservation_status_counts() {
  local success_count=0
  local non_success_count=0
  local indexed_count=0
  local reservation_order_ids
  local reserved_order_id
  local reserved_status
  local indexed_score
  if ! reservation_order_ids="$(scan_reservation_order_ids)"; then
    return 1
  fi
  if [[ -n "$reservation_order_ids" ]]; then
    while IFS= read -r reserved_order_id; do
      [[ -z "$reserved_order_id" ]] && continue
      reserved_status="$(redis_scalar HGET "seckill:order:status:${reserved_order_id}" status)"
      if [[ "$reserved_status" == "SUCCESS" ]]; then
        success_count=$((success_count + 1))
      else
        non_success_count=$((non_success_count + 1))
      fi
      indexed_score="$(redis_scalar ZSCORE "seckill:order:processing" "$reserved_order_id")"
      if [[ -n "$indexed_score" ]]; then
        indexed_count=$((indexed_count + 1))
      fi
    done <<< "$reservation_order_ids"
  fi
  printf '%s %s %s\n' "$success_count" "$non_success_count" "$indexed_count"
}

scan_reservation_order_ids() {
  local cursor=0
  local scan_output
  local i
  local -a scan_rows

  while true; do
    scan_output="$(redis_cmd --raw HSCAN "seckill:reservation:${VOUCHER_ID}" "$cursor" COUNT 500)"
    mapfile -t scan_rows <<< "$scan_output"
    if (( ${#scan_rows[@]} == 0 )); then
      echo "Redis HSCAN returned no cursor for seckill:reservation:${VOUCHER_ID}" >&2
      return 1
    fi
    cursor="${scan_rows[0]}"
    # HSCAN rows are cursor, then alternating field/value pairs. Only values are order ids.
    for ((i = 2; i < ${#scan_rows[@]}; i += 2)); do
      printf '%s\n' "${scan_rows[$i]}"
    done
    [[ "$cursor" == "0" ]] && break
  done
}

# ZSCAN keeps this correctness check bounded per Redis round trip; never ZRANGE the entire
# global PROCESSING index merely to inspect one benchmark voucher.
processing_index_count_for_voucher() {
  local cursor=0
  local count=0
  local scan_output
  local indexed_order_id
  local indexed_voucher_id
  local i
  local -a scan_rows

  while true; do
    scan_output="$(redis_cmd --raw ZSCAN "seckill:order:processing" "$cursor" COUNT 500)"
    mapfile -t scan_rows <<< "$scan_output"
    if (( ${#scan_rows[@]} == 0 )); then
      echo "Redis ZSCAN returned no cursor for seckill:order:processing" >&2
      return 1
    fi
    cursor="${scan_rows[0]}"
    for ((i = 1; i + 1 < ${#scan_rows[@]}; i += 2)); do
      indexed_order_id="${scan_rows[$i]}"
      indexed_voucher_id="$(redis_scalar HGET "seckill:order:status:${indexed_order_id}" voucherId)"
      if [[ "$indexed_voucher_id" == "$VOUCHER_ID" ]]; then
        count=$((count + 1))
      fi
    done
    [[ "$cursor" == "0" ]] && break
  done
  printf '%s\n' "$count"
}

drain_start_ms="$(date +%s%3N)"
deadline_ms=$((drain_start_ms + DRAIN_TIMEOUT_MS))
orders=0
redis_success_count=0
redis_non_success_count=0
redis_processing_index_count=0
reservation_counts=""

while true; do
  orders="$(mysql_scalar "SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id = ${VOUCHER_ID};")"
  orders="${orders:-0}"

  if [[ "$orders" -ge "$EXPECTED_ORDERS" ]]; then
    reservation_counts="$(reservation_status_counts)"
    read -r redis_success_count redis_non_success_count redis_processing_index_count \
      <<< "$reservation_counts"
    if [[ "$redis_success_count" == "$EXPECTED_ORDERS" \
      && "$redis_non_success_count" == "0" \
      && "$redis_processing_index_count" == "0" ]]; then
      break
    fi
  fi

  now_ms="$(date +%s%3N)"
  if (( now_ms >= deadline_ms )); then
    echo "Timed out waiting for RocketMQ consumers: orders=${orders}, expected=${EXPECTED_ORDERS}, redis_success=${redis_success_count}, redis_non_success=${redis_non_success_count}, processing_index=${redis_processing_index_count}" >&2
    break
  fi

  sleep "$(python3 - "$POLL_INTERVAL_MS" <<'PY'
import sys
print(int(sys.argv[1]) / 1000.0)
PY
)"
done

wait_for_rocketmq_lag_zero
drain_end_ms="$(date +%s%3N)"
drain_ms=$((drain_end_ms - drain_start_ms))
db_stock="$(mysql_scalar "SELECT stock FROM tb_seckill_voucher WHERE voucher_id = ${VOUCHER_ID};")"
duplicate_orders="$(mysql_scalar "SELECT COUNT(*) FROM (SELECT user_id, COUNT(*) AS cnt FROM tb_voucher_order WHERE voucher_id = ${VOUCHER_ID} GROUP BY user_id HAVING cnt > 1) t;")"
redis_stock="$(redis_scalar GET "seckill:stock:${VOUCHER_ID}")"
redis_order_count="$(redis_scalar SCARD "seckill:order:${VOUCHER_ID}")"
redis_reservation_count="$(redis_scalar HLEN "seckill:reservation:${VOUCHER_ID}")"
redis_activity_status="$(redis_scalar HGET "seckill:meta:${VOUCHER_ID}" status)"
reservation_counts="$(reservation_status_counts)"
read -r redis_success_count redis_non_success_count redis_processing_reservation_count \
  <<< "$reservation_counts"
redis_processing_index_count="$(processing_index_count_for_voucher)"
capture_prometheus after "$PROMETHEUS_AFTER"
RUN_SUMMARY_LINK="$(basename "$RUN_SUMMARY")"

python3 - "$JTL_FILE" "$SUMMARY_CSV" "$AGGREGATE_CSV" <<'PY'
import csv
import math
import statistics
import sys

jtl_path, summary_path, aggregate_path = sys.argv[1:4]

with open(jtl_path, newline="", encoding="utf-8") as f:
    rows = list(csv.DictReader(f))

if not rows:
    raise SystemExit("JTL file has no samples")

def percentile(values, pct):
    if not values:
        return 0
    ordered = sorted(values)
    index = int(math.ceil(pct / 100.0 * len(ordered))) - 1
    index = max(0, min(index, len(ordered) - 1))
    return ordered[index]

def build_stats(label, items):
    elapsed = [int(r["elapsed"]) for r in items]
    timestamps = [int(r["timeStamp"]) for r in items]
    ends = [int(r["timeStamp"]) + int(r["elapsed"]) for r in items]
    bytes_sum = sum(int(r.get("bytes") or 0) for r in items)
    sent_sum = sum(int(r.get("sentBytes") or 0) for r in items)
    duration_sec = max((max(ends) - min(timestamps)) / 1000.0, 0.001)
    errors = sum(1 for r in items if str(r.get("success", "")).lower() != "true")
    avg = sum(elapsed) / len(elapsed)
    stddev = statistics.pstdev(elapsed) if len(elapsed) > 1 else 0.0
    return {
        "Label": label,
        "# Samples": len(items),
        "Average": round(avg),
        "Min": min(elapsed),
        "Max": max(elapsed),
        "Std. Dev.": f"{stddev:.2f}",
        "Error %": f"{(errors / len(items) * 100):.3f}%",
        "Throughput": f"{(len(items) / duration_sec):.5f}",
        "Received KB/sec": f"{(bytes_sum / 1024.0 / duration_sec):.2f}",
        "Sent KB/sec": f"{(sent_sum / 1024.0 / duration_sec):.2f}",
        "Avg. Bytes": f"{(bytes_sum / len(items)):.1f}",
        "Median": percentile(elapsed, 50),
        "90% Line": percentile(elapsed, 90),
        "95% Line": percentile(elapsed, 95),
        "99% Line": percentile(elapsed, 99),
    }

labels = []
by_label = {}
for row in rows:
    label = row["label"]
    if label not in by_label:
        labels.append(label)
        by_label[label] = []
    by_label[label].append(row)

stats = [build_stats(label, by_label[label]) for label in labels]
stats.append(build_stats("TOTAL", rows))

summary_fields = [
    "Label", "# Samples", "Average", "Min", "Max", "Std. Dev.",
    "Error %", "Throughput", "Received KB/sec", "Sent KB/sec", "Avg. Bytes",
]
aggregate_fields = [
    "Label", "# Samples", "Average", "Median", "90% Line", "95% Line",
    "99% Line", "Min", "Max", "Error %", "Throughput",
    "Received KB/sec", "Sent KB/sec",
]

for path, fields in ((summary_path, summary_fields), (aggregate_path, aggregate_fields)):
    with open(path, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=fields, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(stats)
PY

read -r samples avg_ms median_ms p90_ms p95_ms p99_ms min_ms max_ms error_pct throughput <<< "$(
  python3 - "$AGGREGATE_CSV" <<'PY'
import csv
import sys

with open(sys.argv[1], newline="", encoding="utf-8") as f:
    rows = list(csv.DictReader(f))

total = next(row for row in rows if row["Label"] == "TOTAL")
print(
    total["# Samples"],
    total["Average"],
    total["Median"],
    total["90% Line"],
    total["95% Line"],
    total["99% Line"],
    total["Min"],
    total["Max"],
    total["Error %"],
    total["Throughput"],
)
PY
)"

accepted="$(prometheus_delta local_deals_seckill_requests_total result=accepted)"
rejected_stock="$(prometheus_delta local_deals_seckill_requests_total result=rejected_stock)"
rejected_duplicate="$(prometheus_delta local_deals_seckill_requests_total result=rejected_duplicate)"
rejected_rate="$(prometheus_delta local_deals_seckill_requests_total result=rejected_rate)"
unavailable="$(prometheus_delta local_deals_seckill_requests_total result=unavailable)"

expected_redis_stock=$((STOCK - EXPECTED_ORDERS))
expected_db_stock=$((STOCK - EXPECTED_ORDERS))
correctness="pass"
if (( samples != TOTAL_REQUESTS )); then correctness="fail"; fi
if (( orders != EXPECTED_ORDERS )); then correctness="fail"; fi
if (( duplicate_orders != 0 )); then correctness="fail"; fi
if (( db_stock != expected_db_stock )); then correctness="fail"; fi
if [[ "$redis_order_count" != "$EXPECTED_ORDERS" ]]; then correctness="fail"; fi
if [[ "$redis_reservation_count" != "$EXPECTED_ORDERS" ]]; then correctness="fail"; fi
if [[ "$redis_activity_status" != "ACTIVE" ]]; then correctness="fail"; fi
if [[ "$redis_success_count" != "$EXPECTED_ORDERS" ]]; then correctness="fail"; fi
if [[ "$redis_non_success_count" != "0" ]]; then correctness="fail"; fi
if [[ "$redis_processing_reservation_count" != "0" ]]; then correctness="fail"; fi
if [[ "$redis_processing_index_count" != "0" ]]; then correctness="fail"; fi
if [[ "$redis_stock" != "$expected_redis_stock" ]]; then correctness="fail"; fi
if [[ -n "$PROMETHEUS_URL" ]]; then
  [[ "$accepted" =~ ^[0-9]+([.][0-9]+)?$ ]] || correctness="fail"
  [[ "$rejected_stock" =~ ^[0-9]+([.][0-9]+)?$ ]] || correctness="fail"
  [[ "$rejected_duplicate" =~ ^[0-9]+([.][0-9]+)?$ ]] || correctness="fail"
  [[ "$rejected_rate" =~ ^[0-9]+([.][0-9]+)?$ ]] || correctness="fail"
  [[ "$unavailable" =~ ^[0-9]+([.][0-9]+)?$ ]] || correctness="fail"
  [[ "$accepted" == "$EXPECTED_ORDERS" ]] || correctness="fail"
  [[ "$rejected_stock" == "$((TOTAL_REQUESTS - EXPECTED_ORDERS))" ]] || correctness="fail"
  [[ "$rejected_duplicate" == "0" ]] || correctness="fail"
  [[ "$rejected_rate" == "0" ]] || correctness="fail"
  [[ "$unavailable" == "0" ]] || correctness="fail"
fi
if [[ -n "$M7RC_BROKER_CONTAINER" ]]; then
  [[ "$MQ_MAIN_LAG" == "0" && "$MQ_RETRY_LAG" == "0" && "$MQ_DLQ_LAG" == "0" ]] || correctness="fail"
fi

export DATE RUN_ID SCENARIO THREADS LOOPS STOCK USER_COUNT EXPECTED_ORDERS VOUCHER_ID ROUND
export IMPLEMENTATION="$IMPL"
export RAMP_UP_SECONDS="$RAMP_UP"
export TOTAL_REQUESTS="$TOTAL_REQUESTS"
export SAMPLES="$samples"
export THROUGHPUT="$throughput"
export AVG_MS="$avg_ms"
export MEDIAN_MS="$median_ms"
export P90_MS="$p90_ms"
export P95_MS="$p95_ms"
export P99_MS="$p99_ms"
export MIN_MS="$min_ms"
export MAX_MS="$max_ms"
export ERROR_PCT="$error_pct"
export JMETER_ELAPSED_MS="$jmeter_elapsed_ms"
export DRAIN_MS="$drain_ms"
export POLL_INTERVAL_MS="$POLL_INTERVAL_MS"
export MYSQL_ORDERS="$orders"
export MYSQL_STOCK="$db_stock"
export DUPLICATE_ORDERS="$duplicate_orders"
export REDIS_STOCK="$redis_stock"
export REDIS_ORDER_COUNT="$redis_order_count"
export REDIS_RESERVATION_COUNT="$redis_reservation_count"
export REDIS_ACTIVITY_STATUS="$redis_activity_status"
export REDIS_SUCCESS_COUNT="$redis_success_count"
export REDIS_NON_SUCCESS_COUNT="$redis_non_success_count"
export REDIS_PROCESSING_INDEX_COUNT="$redis_processing_index_count"
export ACCEPTED="$accepted"
export REJECTED_STOCK="$rejected_stock"
export REJECTED_DUPLICATE="$rejected_duplicate"
export REJECTED_RATE="$rejected_rate"
export UNAVAILABLE="$unavailable"
export MQ_MAIN_LAG MQ_RETRY_LAG MQ_DLQ_LAG
export CORRECTNESS="$correctness"
export METRIC_RUN_SUMMARY="$RUN_SUMMARY_REL"
export METRIC_JTL_FILE="$JTL_FILE_REL"
export METRIC_SUMMARY_CSV="$SUMMARY_CSV_REL"
export METRIC_AGGREGATE_CSV="$AGGREGATE_CSV_REL"
export METRIC_HTML_REPORT="$([[ "$SKIP_HTML" -eq 0 ]] && printf '%s' "${HTML_REPORT_DIR_REL}" || printf 'skipped')"

python3 - "$METRICS_CSV" <<'PY'
import csv
import os
import sys

path = sys.argv[1]
fields = [
    "date", "run_id", "scenario", "implementation", "threads", "loops",
    "ramp_up_seconds", "total_requests", "stock", "user_count",
    "expected_orders", "voucher_id", "round", "samples", "throughput",
    "avg_ms", "median_ms", "p90_ms", "p95_ms", "p99_ms", "min_ms",
    "max_ms", "error_pct", "jmeter_elapsed_ms", "drain_ms",
    "poll_interval_ms", "mysql_orders", "mysql_stock", "duplicate_orders",
    "redis_stock", "redis_order_count", "redis_reservation_count",
    "redis_activity_status", "redis_success_count", "redis_non_success_count",
    "redis_processing_index_count",
    "accepted", "rejected_stock", "rejected_duplicate", "rejected_rate", "unavailable",
    "mq_main_lag", "mq_retry_lag", "mq_dlq_lag",
    "correctness", "run_summary", "jtl_file", "summary_csv",
    "aggregate_csv", "html_report",
]
row = {field: os.environ.get(field.upper(), "") for field in fields}
row["run_summary"] = os.environ.get("METRIC_RUN_SUMMARY", "")
row["jtl_file"] = os.environ.get("METRIC_JTL_FILE", "")
row["summary_csv"] = os.environ.get("METRIC_SUMMARY_CSV", "")
row["aggregate_csv"] = os.environ.get("METRIC_AGGREGATE_CSV", "")
row["html_report"] = os.environ.get("METRIC_HTML_REPORT", "")
existing = []
if os.path.exists(path) and os.path.getsize(path) > 0:
    with open(path, newline="", encoding="utf-8") as f:
        existing = [r for r in csv.DictReader(f) if r.get("run_id") != row["run_id"]]

with open(path, "w", newline="", encoding="utf-8") as f:
    writer = csv.DictWriter(f, fieldnames=fields)
    writer.writeheader()
    writer.writerows(existing)
    writer.writerow(row)
PY

cat > "$RUN_SUMMARY" <<EOF
# Seckill Benchmark Run Summary

- run_id: ${RUN_ID}
- date: ${DATE}
- scenario: ${SCENARIO}
- implementation: ${IMPL}
- voucher_id: ${VOUCHER_ID}
- message_transport: RocketMQ transaction message
- stock: ${STOCK}
- expected_orders: ${EXPECTED_ORDERS}
- threads: ${THREADS}
- loops: ${LOOPS}
- ramp_up_seconds: ${RAMP_UP}
- total_requests: ${TOTAL_REQUESTS}
- samples: ${samples}
- throughput: ${throughput}
- avg_ms: ${avg_ms}
- median_ms: ${median_ms}
- p90_ms: ${p90_ms}
- p95_ms: ${p95_ms}
- p99_ms: ${p99_ms}
- max_ms: ${max_ms}
- error_pct: ${error_pct}
- jmeter_elapsed_ms: ${jmeter_elapsed_ms}
- drain_ms: ${drain_ms}
- accepted: ${accepted}
- rejected_stock: ${rejected_stock}
- rejected_duplicate: ${rejected_duplicate}
- rejected_rate: ${rejected_rate}
- unavailable: ${unavailable}
- rocketmq_main_lag: ${MQ_MAIN_LAG}
- rocketmq_retry_lag: ${MQ_RETRY_LAG}
- rocketmq_dlq_lag: ${MQ_DLQ_LAG}
- poll_interval_ms: ${POLL_INTERVAL_MS}
- java_home: ${JAVA_HOME}
- maven_cmd: ${MAVEN_CMD}
- project_dir: ${PROJECT_DIR}
- output_root: ${OUTPUT_ROOT}
- mysql_host: ${MYSQL_HOST}
- mysql_database: ${MYSQL_DATABASE}
- redis_host: ${REDIS_HOST}
- mysql_orders: ${orders}
- mysql_stock: ${db_stock}
- duplicate_orders: ${duplicate_orders}
- redis_stock: ${redis_stock}
- redis_order_count: ${redis_order_count}
- redis_reservation_count: ${redis_reservation_count}
- redis_activity_status: ${redis_activity_status}
- redis_success_count: ${redis_success_count}
- redis_non_success_count: ${redis_non_success_count}
- redis_processing_index_count: ${redis_processing_index_count}
- correctness: ${correctness}
- metrics_csv: ${METRICS_CSV_REL}
- jtl_file: ${JTL_FILE_REL}
- summary_csv: ${SUMMARY_CSV_REL}
- aggregate_csv: ${AGGREGATE_CSV_REL}
- html_report: $([[ "$SKIP_HTML" -eq 0 ]] && printf '%s' "${HTML_REPORT_DIR_REL}" || printf 'skipped')

## Markdown Row

| ${DATE} | ${IMPL} | ${SCENARIO} | ${THREADS} 线程 / ${LOOPS} 次循环 | ${STOCK} | ${TOTAL_REQUESTS} | ${throughput} | ${p95_ms} / ${p99_ms} | ${drain_ms} | ${orders} / ${EXPECTED_ORDERS} | n/a | n/a | ${correctness} | [run-summary](${RUN_SUMMARY_LINK}) |
EOF

if [[ "$SKIP_HTML" -eq 0 ]]; then
  if ! jmeter -g "$JTL_FILE" -o "$HTML_REPORT_DIR"; then
    echo "Warning: failed to generate HTML report at ${HTML_REPORT_DIR}" >&2
  fi
fi

cat "$RUN_SUMMARY"

if [[ "$correctness" != "pass" ]]; then
  echo "Seckill benchmark correctness gate failed; evidence was preserved in ${RUN_SUMMARY}" >&2
  exit 1
fi

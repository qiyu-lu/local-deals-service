#!/usr/bin/env bash
set -euo pipefail

SCRIPT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROJECT_DIR="$SCRIPT_ROOT"
OUTPUT_ROOT="$SCRIPT_ROOT"

DATE="$(date +%F)"
MODULE="seckill"
IMPL="reliable-stream-v1"
SCENARIO="seckill-reliability"
EXPECTATION="current"
ROUND=""
STOCK=10
VOUCHER_ID=""
FAULT_USER_ID=1
WAIT_MS=15000
POLL_MS=200
REDIS_HOST="${REDIS_HOST:-${LOCAL_DEALS_REDIS_HOST:-localhost}}"
REDIS_PORT="${REDIS_PORT:-${LOCAL_DEALS_REDIS_PORT:-6379}}"
MYSQL_DATABASE="${MYSQL_DATABASE:-local_deals}"
MYSQL_USER="${MYSQL_USER:-${LOCAL_DEALS_DATASOURCE_USERNAME:-root}}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-${LOCAL_DEALS_DATASOURCE_PASSWORD:-}}"
REDIS_PASSWORD="${REDIS_PASSWORD:-${LOCAL_DEALS_REDIS_PASSWORD:-}}"
STREAM_KEY="stream.orders"
STREAM_GROUP="g1"
DEAD_LETTER_KEY="stream.orders.dlq"
RETRY_KEY_PATTERN="seckill:stream:retry:*"
JAVA_HOME="${JAVA_HOME:-/home/sd101t/.jdks/dragonwell-ex-1.8.0_472}"
MAVEN_CMD="${MAVEN_CMD:-}"

usage() {
  cat <<'EOF'
Usage:
  scripts/run-seckill-reliability-check.sh [options]

Options:
  --project-dir PATH       Project checkout to run Maven fixture reset from. Default: this repo.
  --output-root PATH       Repo root where reliability artifacts are written. Default: this repo.
  --impl NAME              Implementation label. Default: reliable-stream-v1.
  --expect current|baseline
                           Expected behavior. current expects DLQ closure; baseline expects pending residue.
  --scenario NAME          Output label. Default: seckill-reliability.
  --round N                Same-scenario run number. Default: next available round.
  --stock N                Benchmark seckill stock for fixture reset. Default: 10.
  --voucher-id N           Existing seckill voucher id. If omitted, BenchmarkDataTool resolves one.
  --fault-user-id N        userId field used in malformed message. Default: 1.
  --wait-ms N              Max wait for consumer behavior. Default: 15000.
  --poll-ms N              Redis polling interval. Default: 200.
  --redis-host HOST        Redis host. Default: localhost (override via REDIS_HOST or LOCAL_DEALS_REDIS_HOST in .env)
  --redis-port PORT        Redis port. Default: 6379
  --mysql-database NAME    MySQL database name recorded in output. Default: local_deals.
  --maven-cmd PATH         Maven executable path. Default: mvn, ./mvnw, or IDEA bundled Maven.
  --java-home PATH         JAVA_HOME for Maven benchmark helpers. Default: Dragonwell JDK 8 in ~/.jdks.
  -h, --help               Show this help.

The target service must already be running on the same Redis used here. The
script resets benchmark data, injects a malformed Redis Stream message without
orderId, then records whether the message is closed by dead-letter handling or
left in pending-list.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --project-dir) PROJECT_DIR="$2"; shift 2 ;;
    --output-root) OUTPUT_ROOT="$2"; shift 2 ;;
    --impl) IMPL="$2"; shift 2 ;;
    --expect) EXPECTATION="$2"; shift 2 ;;
    --scenario) SCENARIO="$2"; shift 2 ;;
    --round) ROUND="$2"; shift 2 ;;
    --stock) STOCK="$2"; shift 2 ;;
    --voucher-id) VOUCHER_ID="$2"; shift 2 ;;
    --fault-user-id) FAULT_USER_ID="$2"; shift 2 ;;
    --wait-ms) WAIT_MS="$2"; shift 2 ;;
    --poll-ms) POLL_MS="$2"; shift 2 ;;
    --redis-host) REDIS_HOST="$2"; shift 2 ;;
    --redis-port) REDIS_PORT="$2"; shift 2 ;;
    --mysql-database) MYSQL_DATABASE="$2"; shift 2 ;;
    --maven-cmd) MAVEN_CMD="$2"; shift 2 ;;
    --java-home) JAVA_HOME="$2"; shift 2 ;;
    --stream-key) STREAM_KEY="$2"; shift 2 ;;
    --stream-group) STREAM_GROUP="$2"; shift 2 ;;
    --dead-letter-key) DEAD_LETTER_KEY="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage; exit 1 ;;
  esac
done

if [[ "$EXPECTATION" != "current" && "$EXPECTATION" != "baseline" ]]; then
  echo "--expect must be current or baseline" >&2
  exit 1
fi

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

require_secret() {
  local name="$1"
  if [[ -z "${!name:-}" ]]; then
    echo "Missing required environment variable: ${name}. Source .env first, or export it before running this script." >&2
    exit 1
  fi
}

require_cmd redis-cli
require_cmd python3
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

RESULT_DIR_REL="benchmark/redis-stream-reliability"
RESULT_DIR="${OUTPUT_ROOT}/${RESULT_DIR_REL}"
RUN_ID_PREFIX="${DATE}-${MODULE}-${IMPL}-fault-injection"
if [[ -z "$ROUND" ]]; then
  ROUND=1
  while [[ -e "${RESULT_DIR}/${RUN_ID_PREFIX}-r${ROUND}.md" ]]; do
    ROUND=$((ROUND + 1))
  done
fi
RUN_ID="${RUN_ID_PREFIX}-r${ROUND}"
RUN_SUMMARY_REL="${RESULT_DIR_REL}/${RUN_ID}.md"
RUN_SUMMARY="${OUTPUT_ROOT}/${RUN_SUMMARY_REL}"
METRICS_CSV="${RESULT_DIR}/metrics.csv"
mkdir -p "$RESULT_DIR"

redis_cli() {
  redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" -a "$REDIS_PASSWORD" --no-auth-warning --raw "$@" 2>/dev/null
}

redis_scalar() {
  redis_cli "$@" | sed -n '1p'
}

clear_reliability_keys() {
  redis_cli DEL "$DEAD_LETTER_KEY" >/dev/null || true
  local retry_keys
  retry_keys="$(redis_cli KEYS "$RETRY_KEY_PATTERN" || true)"
  if [[ -n "$retry_keys" ]]; then
    while IFS= read -r key; do
      [[ -n "$key" ]] && redis_cli DEL "$key" >/dev/null || true
    done <<< "$retry_keys"
  fi
}

reset_cmd=(
  "$MAVEN_CMD"
  -Dtest=BenchmarkDataTool#resetSeckillBenchmarkData
  -Dbench.stock="$STOCK"
  test
)
if [[ -n "$VOUCHER_ID" ]]; then
  reset_cmd=(
    "$MAVEN_CMD"
    -Dtest=BenchmarkDataTool#resetSeckillBenchmarkData
    -Dbench.stock="$STOCK"
    -Dbench.voucherId="$VOUCHER_ID"
    test
  )
fi

reset_log="${RESULT_DIR}/${RUN_ID}-reset.log"
reset_output="$("${reset_cmd[@]}" 2>&1 | tee "$reset_log")"
if [[ -z "$VOUCHER_ID" ]]; then
  VOUCHER_ID="$(printf '%s\n' "$reset_output" | sed -n 's/.*voucherId=\([0-9][0-9]*\).*/\1/p' | tail -1)"
fi
if [[ -z "$VOUCHER_ID" ]]; then
  echo "voucherId was not provided and could not be parsed from reset output." >&2
  exit 1
fi

clear_reliability_keys

INJECTED_RECORD_ID="$(redis_cli XADD "$STREAM_KEY" '*' userId "$FAULT_USER_ID" voucherId "$VOUCHER_ID" | tail -1)"
if [[ -z "$INJECTED_RECORD_ID" ]]; then
  echo "Failed to inject malformed stream message." >&2
  exit 1
fi

deadline_ms=$(($(date +%s%3N) + WAIT_MS))
pending="0"
dead_letters="0"
stream_len="0"
retry_key_count="0"
correctness="fail"

while true; do
  pending="$(redis_scalar XPENDING "$STREAM_KEY" "$STREAM_GROUP")"
  dead_letters="$(redis_scalar XLEN "$DEAD_LETTER_KEY")"
  stream_len="$(redis_scalar XLEN "$STREAM_KEY")"
  retry_key_count="$(redis_cli KEYS "$RETRY_KEY_PATTERN" | sed '/^$/d' | wc -l | tr -d ' ')"
  pending="${pending:-0}"
  dead_letters="${dead_letters:-0}"
  stream_len="${stream_len:-0}"
  retry_key_count="${retry_key_count:-0}"

  if [[ "$EXPECTATION" == "current" ]]; then
    if [[ "$pending" == "0" && "$dead_letters" != "0" ]]; then
      correctness="pass"
      break
    fi
  else
    if [[ "$pending" != "0" && "$dead_letters" == "0" ]]; then
      correctness="pass"
      break
    fi
  fi

  now_ms="$(date +%s%3N)"
  if (( now_ms >= deadline_ms )); then
    break
  fi

  sleep "$(python3 - "$POLL_MS" <<'PY'
import sys
print(int(sys.argv[1]) / 1000.0)
PY
)"
done

DEAD_LETTER_SAMPLE="$(redis_cli XRANGE "$DEAD_LETTER_KEY" - + COUNT 1 || true)"

export DATE RUN_ID SCENARIO IMPL EXPECTATION PROJECT_DIR OUTPUT_ROOT MYSQL_HOST MYSQL_DATABASE REDIS_HOST
export STREAM_KEY STREAM_GROUP DEAD_LETTER_KEY VOUCHER_ID FAULT_USER_ID INJECTED_RECORD_ID
export WAIT_MS POLL_MS PENDING="$pending" DEAD_LETTERS="$dead_letters" STREAM_LEN="$stream_len"
export RETRY_KEY_COUNT="$retry_key_count" CORRECTNESS="$correctness" METRIC_RUN_SUMMARY="$RUN_SUMMARY_REL"

python3 - "$METRICS_CSV" <<'PY'
import csv
import os
import sys

path = sys.argv[1]
fields = [
    "date", "run_id", "scenario", "impl", "expectation", "project_dir",
    "mysql_host", "mysql_database", "redis_host", "stream_key",
    "stream_group", "dead_letter_key", "voucher_id", "fault_user_id",
    "injected_record_id", "wait_ms", "poll_ms", "pending", "dead_letters",
    "stream_len", "retry_key_count", "correctness", "run_summary",
]
row = {field: os.environ.get(field.upper(), "") for field in fields}
row["run_summary"] = os.environ.get("METRIC_RUN_SUMMARY", "")
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
# Seckill Reliability Check

- run_id: ${RUN_ID}
- date: ${DATE}
- scenario: ${SCENARIO}
- implementation: ${IMPL}
- expectation: ${EXPECTATION}
- project_dir: ${PROJECT_DIR}
- output_root: ${OUTPUT_ROOT}
- mysql_host: ${MYSQL_HOST}
- mysql_database: ${MYSQL_DATABASE}
- redis_host: ${REDIS_HOST}
- stream_key: ${STREAM_KEY}
- stream_group: ${STREAM_GROUP}
- dead_letter_key: ${DEAD_LETTER_KEY}
- voucher_id: ${VOUCHER_ID}
- fault_user_id: ${FAULT_USER_ID}
- injected_record_id: ${INJECTED_RECORD_ID}
- malformed_payload: userId=${FAULT_USER_ID}, voucherId=${VOUCHER_ID}, missing orderId
- wait_ms: ${WAIT_MS}
- poll_ms: ${POLL_MS}
- stream_len: ${stream_len}
- stream_pending: ${pending}
- stream_dead_letters: ${dead_letters}
- retry_key_count: ${retry_key_count}
- correctness: ${correctness}
- reset_log: ${RESULT_DIR_REL}/${RUN_ID}-reset.log
- metrics_csv: ${RESULT_DIR_REL}/metrics.csv

## Dead Letter Sample

\`\`\`text
${DEAD_LETTER_SAMPLE}
\`\`\`

## Markdown Row

| ${DATE} | ${IMPL} | ${EXPECTATION} | ${VOUCHER_ID} | ${INJECTED_RECORD_ID} | ${pending} | ${dead_letters} | ${retry_key_count} | ${correctness} | [run-summary](${RUN_SUMMARY_REL#docs/}) |
EOF

cat "$RUN_SUMMARY"

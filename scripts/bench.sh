#!/usr/bin/env bash
# Open-model benchmark driver for an isolated stack started with scripts/stack.sh.
#
#   scripts/bench.sh users N                    create N users + login tokens (fixture)
#   scripts/bench.sh step RATE...               one fresh voucher per RATE, k6 constant-arrival-rate,
#                                               then wait for the consumer to persist every accepted order
#   scripts/bench.sh drain STOCK RATE           persistence-rate run: STOCK orders admitted at RATE/s,
#                                               measures how fast MySQL catches up
#   scripts/bench.sh profile SECONDS NAME       async-profiler flame graph of the running app
#
#   scripts/bench.sh m3 | m3-smoke              unattended scenario (see "Scenarios" below): own stack,
#   scripts/bench.sh m4 | m4-smoke              M4 scenario: drains A/B, consume-parameter sweep, drills
#   scripts/bench.sh m5 | m5-smoke              M5 scenario: buckets + Redis Cluster A/B, bucket sweep,
#                                               app kill drill and the Redis master kill drill
#                                               builds, warm-up, ladder, drains, crash drills, cleanup;
#                                               results in benchmark/v2/m3/<timestamp>-<scenario>/
#
# Every run writes raw k6 JSON/logs under benchmark/v2/<MILESTONE>/raw/ (gitignored) and appends
# one row per run to benchmark/v2/<MILESTONE>/summary.csv. The commit column is HEAD unless
# BENCH_COMMIT names the build under test (e.g. a jar built from an older tag).
#
# CPU isolation: the app runs under taskset (APP_CPUS, see stack.sh), dependency containers are
# pinned with DEPS_CPUS, and k6 runs in a container pinned to K6_CPUS. CPU columns are cores used
# on average during the k6 window, from /proc/<pid>/stat and cgroup cpuacct.usage deltas.
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
case "${1:-}" in
  m3|m3-smoke|m4|m4-smoke|m5|m5-smoke)
    # Scenarios own a separate stack, so they never touch the stack used for integration tests.
    export STACK_ID="${STACK_ID:-m3bench}" MYSQL_PORT="${MYSQL_PORT:-33306}" REDIS_PORT="${REDIS_PORT:-36379}" \
      NAMESRV_PORT="${NAMESRV_PORT:-39876}" BROKER_PORT="${BROKER_PORT:-30911}" ES_PORT="${ES_PORT:-39200}" \
      APP_PORT="${APP_PORT:-38083}" MANAGEMENT_PORT="${MANAGEMENT_PORT:-38184}" \
      STACK_SUBNET="${STACK_SUBNET:-172.30.58.0/24}" \
      REDIS_CLUSTER_PORT_BASE="${REDIS_CLUSTER_PORT_BASE:-3700}" \
      REDIS_CLUSTER_BUS_BASE="${REDIS_CLUSTER_BUS_BASE:-4700}" ;;
esac
MILESTONE="${MILESTONE:-m0}"
OUT_DIR="${BENCH_OUT:-${PROJECT_DIR}/benchmark/v2/${MILESTONE}}"
RAW_DIR="${OUT_DIR}/raw"
SUMMARY="${OUT_DIR}/summary.csv"
STACK_ID="${STACK_ID:-v2}"
STACK_NAME="ld-${STACK_ID}"
RUN_DIR="${PROJECT_DIR}/benchmark/v2/run/${STACK_ID}"
K6_IMAGE="${K6_IMAGE:-grafana/k6:2.2.0}"
K6_CPUS="${K6_CPUS:-12-15}"
DURATION="${DURATION:-30s}"
STOCK="${STOCK:-1000}"
DRAIN_TIMEOUT="${DRAIN_TIMEOUT:-900}"
AP_VERSION=3.0
AP_DIR="${PROJECT_DIR}/benchmark/v2/tools/async-profiler-${AP_VERSION}-linux-x64"
USER_CURSOR_FILE="${RUN_DIR}/user-cursor"

fail() { echo "bench: $*" >&2; exit 1; }
eval "$("${PROJECT_DIR}/scripts/stack.sh" env)"
FIXTURE="${PROJECT_DIR}/benchmark/v2/scripts/fixture.py"
mkdir -p "$RAW_DIR" "$RUN_DIR"

app_pid() {
  [[ -f "${RUN_DIR}/app.pid" ]] || fail "app is not running (scripts/stack.sh app-start)"
  cat "${RUN_DIR}/app.pid"
}

# utime+stime of a pid in clock ticks
proc_ticks() { awk '{print $14 + $15}' "/proc/$1/stat"; }
container_ns() {
  local id
  id="$(docker inspect -f '{{.Id}}' "$1")"
  cat "/sys/fs/cgroup/cpu,cpuacct/docker/${id}/cpuacct.usage"
}

# The Redis column is the whole Redis layer: one node, or the six of a cluster when the run
# under measurement talks to it (BENCH_REDIS_CLUSTER).
redis_ns() {
  local total=0 i
  if [[ -n "${BENCH_REDIS_CLUSTER:-}" ]]; then
    for i in 1 2 3 4 5 6; do
      total=$(( total + $(container_ns "${STACK_NAME}-redis-c${i}" 2>/dev/null || echo 0) ))
    done
    echo "$total"
  else
    container_ns "${STACK_NAME}-redis"
  fi
}

# Sum of max offsets over all queues of a topic: messages ever written to it.
topic_offset() {
  docker exec "${STACK_NAME}-broker" sh mqadmin topicStatus -n namesrv:9876 -t "$1" 2>/dev/null |
    awk -v b="$STACK_NAME" '$1 == b { s += $4 } END { print s + 0 }'
}

token_count() { wc -l <"${PROJECT_DIR}/benchmark/v2/run/tokens.csv"; }

# Each run starts where the previous one stopped so a user never repeats inside the
# per-user rate-limit window; the pool wraps around only after all tokens were used.
next_user_offset() {
  local cursor=0
  [[ -f "$USER_CURSOR_FILE" ]] && cursor="$(<"$USER_CURSOR_FILE")"
  echo "$cursor"
}
advance_user_cursor() { echo $(( ($(next_user_offset) + $1) % $(token_count) )) >"$USER_CURSOR_FILE"; }

run_k6() { # name voucher rate duration
  local name="$1" voucher="$2" rate="$3" duration="$4" offset
  offset="$(next_user_offset)"
  # The load generator's own CPU goes into the summary: a saturated client caps the ladder too.
  local container="${STACK_NAME}-k6-$$"
  ( while ! id="$(docker inspect -f '{{.Id}}' "$container" 2>/dev/null)"; do sleep 0.2; done
    f="/sys/fs/cgroup/cpu,cpuacct/docker/${id}/cpuacct.usage"
    while [[ -r "$f" ]] && v="$(cat "$f" 2>/dev/null)"; do echo "$v" >"${RAW_DIR}/${name}.k6ns"; sleep 0.5; done
  ) &
  local sampler=$!
  docker run --rm --name "$container" --network host --cpuset-cpus "$K6_CPUS" --user "$(id -u):$(id -g)" \
    -v "${PROJECT_DIR}/benchmark/v2/scripts:/scripts:ro" \
    -v "${PROJECT_DIR}/benchmark/v2/run:/data:ro" \
    -v "${RAW_DIR}:/out" \
    "$K6_IMAGE" run --quiet \
    -e BASE_URL="$STACK_APP" -e VOUCHER_ID="$voucher" -e RATE="$rate" -e DURATION="$duration" \
    -e TOKENS=/data/tokens.csv -e USER_OFFSET="$offset" \
    ${SECKILL_TOKEN_SECRET:+-e SECKILL_TOKEN_SECRET="$SECKILL_TOKEN_SECRET"} \
    --summary-export "/out/${name}.json" /scripts/seckill.js >"${RAW_DIR}/${name}.log" 2>&1 || true
  kill "$sampler" 2>/dev/null || true; wait "$sampler" 2>/dev/null || true
  [[ -s "${RAW_DIR}/${name}.json" ]] || fail "k6 produced no summary; see ${RAW_DIR}/${name}.log"
}

wait_drain() { # voucher accepted -> prints seconds until MySQL holds every accepted order
  local voucher="$1" accepted="$2" start now count
  start="$(date +%s.%N)"
  while :; do
    count="$(python3 "$FIXTURE" orders "$voucher")"
    now="$(date +%s.%N)"
    if (( count >= accepted )); then
      python3 -c "print(f'{${now}-${start}:.1f}')"
      return
    fi
    if python3 -c "import sys; sys.exit(0 if ${now}-${start} > ${DRAIN_TIMEOUT} else 1)"; then
      echo "timeout(${count}/${accepted})"
      return
    fi
    sleep 1
  done
}

sample_orders() { # voucher file -> "epoch_seconds,count" once per second while this script lives
  while kill -0 $$ 2>/dev/null; do
    echo "$(date +%s.%N),$(python3 "$FIXTURE" orders "$1")" >>"$2"
    sleep 1
  done
}

one_run() { # kind rate stock duration
  local kind="$1" rate="$2" stock="$3" duration="$4"
  local voucher name pid t0 t1 ticks0 ticks1 hz ns0 ns1 svc started ended
  local -A dep0 dep1
  voucher="$(python3 "$FIXTURE" voucher "$stock")"  # honours BENCH_REDIS_CLUSTER/SECKILL_BUCKETS
  name="${kind}-r${rate}-s${stock}-$(date +%H%M%S)"
  pid="$(app_pid)"
  hz="$(getconf CLK_TCK)"
  local half0 half1 msg0 msg1
  half0="$(topic_offset RMQ_SYS_TRANS_HALF_TOPIC)"; msg0="$(topic_offset seckill-order-topic)"
  for svc in mysql broker; do dep0[$svc]="$(container_ns "${STACK_NAME}-${svc}")"; done
  dep0[redis]="$(redis_ns)"
  sample_orders "$voucher" "${RAW_DIR}/${name}-orders.csv" &
  local sampler=$!
  ticks0="$(proc_ticks "$pid")"; started="$(date +%s.%N)"
  run_k6 "$name" "$voucher" "$rate" "$duration"
  ticks1="$(proc_ticks "$pid")"; ended="$(date +%s.%N)"
  for svc in mysql broker; do dep1[$svc]="$(container_ns "${STACK_NAME}-${svc}")"; done
  dep1[redis]="$(redis_ns)"
  local total
  total="$(python3 -c "import json;print(int(json.load(open('${RAW_DIR}/${name}.json'))['metrics']['iterations']['count']))")"
  advance_user_cursor "$total"
  local accepted drain
  accepted="$(python3 - "${RAW_DIR}/${name}.json" <<'PY'
import json, sys
m = json.load(open(sys.argv[1]))['metrics']
print(int(m.get('outcome_accepted', {}).get('count', 0)))
PY
)"
  drain="$(wait_drain "$voucher" "$accepted")"
  sleep 1; kill "$sampler" 2>/dev/null || true; wait "$sampler" 2>/dev/null || true
  half1="$(topic_offset RMQ_SYS_TRANS_HALF_TOPIC)"; msg1="$(topic_offset seckill-order-topic)"
  python3 - "$SUMMARY" "${RAW_DIR}/${name}.json" "$kind" "$rate" "$stock" "$voucher" "$drain" \
    "$(python3 -c "print(round(($ticks1-$ticks0)/$hz/($ended-$started),2))")" \
    "$(python3 -c "print(round(($ended-$started),1))")" \
    "${dep0[mysql]}:${dep1[mysql]}" "${dep0[redis]}:${dep1[redis]}" "${dep0[broker]}:${dep1[broker]}" \
    "${BENCH_COMMIT:-$(git -C "$PROJECT_DIR" rev-parse --short HEAD)}" \
    "$(( half1 - half0 ))" "$(( msg1 - msg0 ))" <<'PY'
import csv, json, os, sys
summary, raw, kind, rate, stock, voucher, drain, app_cpu, wall, mysql_ns, redis_ns, broker_ns, commit, \
    half_msgs, order_msgs = sys.argv[1:]
samples = [tuple(float(x) for x in line.split(',')) for line in open(raw[:-5] + '-orders.csv') if ',' in line]
m = json.load(open(raw))['metrics']
wall = float(wall)
def cores(pair):
    a, b = (int(x) for x in pair.split(':'))
    return round((b - a) / 1e9 / wall, 2)
def val(metric, key, default=0):
    return m.get(metric, {}).get(key, default)
def k6_cores():
    try:
        return round(int(open(raw[:-5] + '.k6ns').read()) / 1e9 / wall, 2)
    except (OSError, ValueError):
        return ''
outcomes = {k[len('outcome_'):]: int(v['count']) for k, v in m.items() if k.startswith('outcome_')}
accepted = outcomes.get('accepted', 0)
def persist_rate():
    # slope between the samples where 10% and 90% of accepted orders had been persisted
    if accepted < 50:
        return ''
    lo = next((s for s in samples if s[1] >= 0.1 * accepted), None)
    hi = next((s for s in samples if s[1] >= 0.9 * accepted), None)
    if not lo or not hi or hi[0] <= lo[0]:
        return ''
    return round((hi[1] - lo[1]) / (hi[0] - lo[0]), 1)
drain_s = drain if drain.startswith('timeout') else float(drain)
row = {
    'commit': commit, 'kind': kind, 'target_rps': int(rate), 'stock': int(stock), 'voucher': voucher,
    'achieved_rps': round(val('http_reqs', 'rate'), 1),
    'requests': int(val('http_reqs', 'count')),
    'dropped': int(val('dropped_iterations', 'count')),
    'p50_ms': round(val('http_req_duration', 'med'), 1),
    'p95_ms': round(val('http_req_duration', 'p(95)'), 1),
    'p99_ms': round(val('http_req_duration', 'p(99)'), 1),
    'max_ms': round(val('http_req_duration', 'max'), 1),
    'http_fail_rate': round(val('http_req_failed', 'value'), 4),
    'accepted': accepted,
    'outcomes': ';'.join(f"{k}={v}" for k, v in sorted(outcomes.items())),
    'drain_s_after_load': drain_s,
    'persist_orders_per_s': persist_rate(),
    'app_cpu_cores': float(app_cpu), 'mysql_cpu_cores': cores(mysql_ns),
    'redis_cpu_cores': cores(redis_ns), 'broker_cpu_cores': cores(broker_ns),
    # messages written during the run, drain included: half messages and order messages
    'half_msgs': int(half_msgs), 'order_msgs': int(order_msgs),
    'k6_cpu_cores': k6_cores(),
    'raw': os.path.basename(raw),
}
new = not os.path.exists(summary)
with open(summary, 'a', newline='') as f:
    w = csv.DictWriter(f, fieldnames=list(row))
    if new:
        w.writeheader()
    w.writerow(row)
print(', '.join(f"{k}={v}" for k, v in row.items()))
PY
}

profile() { # seconds name
  local seconds="$1" name="$2" pid
  pid="$(app_pid)"
  if [[ ! -x "${AP_DIR}/bin/asprof" ]]; then
    mkdir -p "$(dirname "$AP_DIR")"
    curl -fsSL "https://github.com/async-profiler/async-profiler/releases/download/v${AP_VERSION}/async-profiler-${AP_VERSION}-linux-x64.tar.gz" |
      tar xz -C "$(dirname "$AP_DIR")"
  fi
  # perf_event_paranoid > 1 on this host, so sample with itimer; wall shows blocked threads too.
  "${AP_DIR}/bin/asprof" -d "$seconds" -e "${AP_EVENT:-itimer}" -f "${RAW_DIR}/${name}.html" "$pid"
  echo "${RAW_DIR}/${name}.html"
}

# ---------------------------------------------------------------------------------------------
# Scenarios: one command, no supervision. Each run gets benchmark/v2/<milestone>/<ts>-<name>/:
#   status          RUNNING, DONE, or FAILED: <phase>: <reason>
#   manifest.json   commits, scenario parameters, CPU pinning, JVM options, machine load
#   run.log         every phase with its start/end time and output
#   summary.csv     one row per ladder step / drain (same columns as the per-milestone file)
#   kill-drill.csv  one row per crash drill
#   raw/            k6 JSON and logs, order samples (gitignored)
# Every phase has a timeout; the first failure stops the run, records why, and the stack and
# app are always torn down. Preflight refuses a dirty worktree (results must map to a commit),
# busy ports and a nearly full disk.
# ---------------------------------------------------------------------------------------------
M3_LIMITS_BASELINE="--local-deals.traffic.seckill.activity-limit=100000 --local-deals.traffic.seckill.ip-limit=100000"
M3_LIMITS_CURRENT="--local-deals.traffic.seckill.ip-limit=100000"
M3_TOKEN_SECRET="bench-seckill-token-secret"

load_scenario() {
  case "$1" in
    m3)
      S_MILESTONE=m3; S_BASELINE_TAG=v2.0-m2; S_BASELINE_FLAVOUR=pre-funnel
      S_WARMUP_RATE=500; S_WARMUP_DURATION=30s
      S_RATES="500 1000 2000 5000 10000 15000 20000 25000 30000"; S_STEP_DURATION=30s; S_STEP_STOCK=1000
      S_DRAIN_ROUNDS=3; S_DRAIN_STOCK=20000; S_DRAIN_RATE=2000
      S_KILL_ROUNDS=2; S_BROKER_KILL_ROUNDS=1; S_KILL_STOCK=20000; S_KILL_RATE=2000; S_KILL_AFTER=6
      S_EXTRA_ARGS=""; S_USERS=100000 ;;
    m3-smoke)
      S_MILESTONE=m3; S_BASELINE_TAG=v2.0-m2; S_BASELINE_FLAVOUR=pre-funnel
      S_WARMUP_RATE=200; S_WARMUP_DURATION=5s
      S_RATES="500 2000"; S_STEP_DURATION=10s; S_STEP_STOCK=200
      S_DRAIN_ROUNDS=1; S_DRAIN_STOCK=2000; S_DRAIN_RATE=1000
      S_KILL_ROUNDS=0; S_BROKER_KILL_ROUNDS=1; S_KILL_STOCK=3000; S_KILL_RATE=1000; S_KILL_AFTER=3
      # only the smoke shortens reconciliation, and only for the drills: a redriving reconciler
      # would turn a drain into a measurement of redelivery instead of persistence
      S_EXTRA_ARGS=""; S_DRILL_ARGS="--local-deals.seckill.reconciliation.initial-delay=5s --local-deals.seckill.reconciliation.fixed-delay=2s --local-deals.seckill.reconciliation.stale-after=10s --local-deals.seckill.reconciliation.retry-delay=5s --local-deals.seckill.reconciliation.batch-size=1000"
      S_USERS=20000 ;;
    m4)
      S_MILESTONE=m4; S_BASELINE_TAG=v2.0-m3; S_BASELINE_FLAVOUR=funnel
      S_WARMUP_RATE=500; S_WARMUP_DURATION=30s
      # M4 changes the consumer, not admission: a short ladder only proves no regression.
      S_RATES="10000 20000"; S_STEP_DURATION=30s; S_STEP_STOCK=1000
      S_DRAIN_ROUNDS=3; S_DRAIN_STOCK=20000; S_DRAIN_RATE=2000
      # batch-size:threads pairs, one drain each; "1:16" is this build with batching switched off
      S_SWEEP="1:16 32:16 64:16 64:32 256:32"
      S_KILL_ROUNDS=1; S_BROKER_KILL_ROUNDS=1; S_KILL_STOCK=20000; S_KILL_RATE=2000; S_KILL_AFTER=6
      S_EXTRA_ARGS=""; S_USERS=100000 ;;
    m5)
      S_MILESTONE=m5; S_BASELINE_TAG=v2.0-m4; S_BASELINE_FLAVOUR=funnel
      S_WARMUP_RATE=500; S_WARMUP_DURATION=30s
      # M5 moves the keys, not the funnel: a short ladder only proves no regression.
      S_RATES="10000 20000"; S_STEP_DURATION=30s; S_STEP_STOCK=1000
      S_DRAIN_ROUNDS=3; S_DRAIN_STOCK=20000; S_DRAIN_RATE=2000
      S_BUCKETS=16
      # bucket counts to sweep on the cluster; 1 is this build with the split switched off
      S_BUCKET_SWEEP="1 8 64"
      S_KILL_ROUNDS=1; S_BROKER_KILL_ROUNDS=0; S_REDIS_KILL_ROUNDS=2
      S_KILL_STOCK=20000; S_KILL_RATE=2000; S_KILL_AFTER=6
      S_EXTRA_ARGS=""; S_USERS=100000 ;;
    m5-smoke)
      S_MILESTONE=m5; S_BASELINE_TAG=v2.0-m4; S_BASELINE_FLAVOUR=funnel
      S_WARMUP_RATE=200; S_WARMUP_DURATION=5s
      S_RATES="2000"; S_STEP_DURATION=10s; S_STEP_STOCK=200
      S_DRAIN_ROUNDS=1; S_DRAIN_STOCK=2000; S_DRAIN_RATE=1000
      S_BUCKETS=8
      S_BUCKET_SWEEP="1"
      S_KILL_ROUNDS=0; S_BROKER_KILL_ROUNDS=0; S_REDIS_KILL_ROUNDS=1
      S_KILL_STOCK=3000; S_KILL_RATE=1000; S_KILL_AFTER=3
      S_EXTRA_ARGS=""; S_DRILL_ARGS="--local-deals.seckill.reconciliation.initial-delay=5s --local-deals.seckill.reconciliation.fixed-delay=2s --local-deals.seckill.reconciliation.stale-after=10s --local-deals.seckill.reconciliation.retry-delay=5s --local-deals.seckill.reconciliation.batch-size=1000"
      S_USERS=20000 ;;
    m4-smoke)
      S_MILESTONE=m4; S_BASELINE_TAG=v2.0-m3; S_BASELINE_FLAVOUR=funnel
      S_WARMUP_RATE=200; S_WARMUP_DURATION=5s
      S_RATES="2000"; S_STEP_DURATION=10s; S_STEP_STOCK=200
      S_DRAIN_ROUNDS=1; S_DRAIN_STOCK=2000; S_DRAIN_RATE=1000
      S_SWEEP="1:8 64:16"
      S_KILL_ROUNDS=0; S_BROKER_KILL_ROUNDS=1; S_KILL_STOCK=3000; S_KILL_RATE=1000; S_KILL_AFTER=3
      S_EXTRA_ARGS=""; S_DRILL_ARGS="--local-deals.seckill.reconciliation.initial-delay=5s --local-deals.seckill.reconciliation.fixed-delay=2s --local-deals.seckill.reconciliation.stale-after=10s --local-deals.seckill.reconciliation.retry-delay=5s --local-deals.seckill.reconciliation.batch-size=1000"
      S_USERS=20000 ;;
    *) fail "unknown scenario $1" ;;
  esac
}

S_RESULT="${S_RESULT:-}"
s_log() { echo "$(date '+%F %T') $*" | tee -a "${S_RESULT}/run.log" >&2; }
s_fail() { echo "FAILED: $*" >"${S_RESULT}/status"; s_log "FAILED: $*"; exit 1; }

# phase NAME TIMEOUT_SECONDS function args... ; the function runs in a re-executed bench.sh
# (timeout cannot run a shell function), output goes to run.log
phase() {
  local name="$1" limit="$2"; shift 2
  s_log "phase ${name} start (timeout ${limit}s)"
  local rc=0
  timeout --kill-after=30 "$limit" "$0" _phase "$@" >>"${S_RESULT}/run.log" 2>&1 || rc=$?
  if (( rc == 124 || rc == 137 )); then s_fail "${name}: timed out after ${limit}s"; fi
  (( rc == 0 )) || s_fail "${name}: exit ${rc} (see run.log)"
  s_log "phase ${name} done"
}

scenario_cleanup() {
  local rc=$?
  [[ -n "$S_RESULT" ]] || return
  "${PROJECT_DIR}/scripts/stack.sh" app-stop >>"${S_RESULT}/run.log" 2>&1 || true
  docker unpause "${STACK_NAME}-broker" >/dev/null 2>&1 || true
  if [[ -z "${KEEP_STACK:-}" ]]; then
    "${PROJECT_DIR}/scripts/stack.sh" down >>"${S_RESULT}/run.log" 2>&1 || true
  fi
  git -C "$PROJECT_DIR" worktree remove --force "${RUN_DIR}/baseline-src" >/dev/null 2>&1 || true
  if (( rc != 0 )) && grep -qx RUNNING "${S_RESULT}/status" 2>/dev/null; then
    echo "FAILED: interrupted (exit ${rc})" >"${S_RESULT}/status"
  fi
  echo "$(date '+%F %T') cleanup done; status: $(cat "${S_RESULT}/status")" >>"${S_RESULT}/run.log"
}

preflight() {
  local dirty
  # earlier result directories are untracked by design and do not count; -uall lists their files
  # individually, so the exclusion also works for a milestone directory that is new as a whole
  dirty="$(git -C "$PROJECT_DIR" status --porcelain -uall -- . ':(exclude)benchmark/v2/*/[0-9]*-*/' ':(exclude)benchmark/v2/*/[0-9]*-*/**')"
  if [[ -n "$dirty" ]]; then
    # ALLOW_DIRTY=1 is for smoke runs only; the dirty files are kept in manifest.json.
    [[ -n "${ALLOW_DIRTY:-}" ]] ||
      s_fail "preflight: the worktree has uncommitted changes; commit them so results map to a commit"
    s_log "preflight: ALLOW_DIRTY set, running with uncommitted changes: ${dirty//$'\n'/; }"
  fi
  local port
  for port in "$MYSQL_PORT" "$REDIS_PORT" "$NAMESRV_PORT" "$BROKER_PORT" "$ES_PORT" "$APP_PORT" "$MANAGEMENT_PORT"; do
    ! ss -ltn "( sport = :${port} )" | grep -q LISTEN || s_fail "preflight: port ${port} is already in use"
  done
  local path free
  for path in "$PROJECT_DIR" "$(dirname "$BROKER_STORE_ROOT_CHECK")"; do
    [[ -d "$path" ]] || continue
    free="$(df -Pk "$path" | awk 'NR==2 {print int($4/1024/1024)}')"
    (( free >= 15 )) || s_fail "preflight: only ${free} GB free under ${path} (need 15)"
  done
  command -v docker >/dev/null && docker info >/dev/null 2>&1 || s_fail "preflight: docker is not usable"
  [[ -x "${APP_JAVA_HOME:-${HOME}/.jdks/temurin-21.0.12.1}/bin/java" ]] || s_fail "preflight: no JDK 21 (APP_JAVA_HOME)"
  command -v mysql >/dev/null && command -v redis-cli >/dev/null || s_fail "preflight: mysql and redis-cli clients are required"
}

write_manifest() { # phase-free: captured at start and again at the end
  python3 - "${S_RESULT}/manifest.json" "$@" <<'PY'
import json, os, subprocess, sys, time
path, when = sys.argv[1], sys.argv[2]
def sh(cmd):
    try:
        return subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=30).stdout.strip()
    except Exception as e:
        return f"error: {e}"
m = json.load(open(path)) if os.path.exists(path) else {}
if when == 'start':
    m.update({
        'scenario': os.environ['S_NAME'],
        'started_at': time.strftime('%Y-%m-%dT%H:%M:%S%z'),
        'commit': sh('git rev-parse HEAD'), 'commit_short': sh('git rev-parse --short HEAD'),
        'uncommitted_changes': sh('git status --porcelain'),
        'baseline': {'tag': os.environ['S_BASELINE_TAG'], 'commit': sh(f"git rev-parse {os.environ['S_BASELINE_TAG']}^{{commit}}")},
        'parameters': {k[2:].lower(): v for k, v in os.environ.items() if k.startswith('S_') and k not in ('S_NAME', 'S_RESULT')},
        'cpu_pinning': {'app': os.environ['APP_CPUS'], 'dependencies': os.environ['DEPS_CPUS'], 'k6': os.environ['K6_CPUS']},
        'jvm_options': os.environ.get('APP_JAVA_OPTS', '-Xms2g -Xmx2g'),
        'app_args': {'baseline': os.environ['ARGS_BASELINE'], 'current': os.environ['ARGS_CURRENT']},
        'stack': {'id': os.environ['STACK_ID'], 'app_port': os.environ['APP_PORT']},
        'k6_image': os.environ['K6_IMAGE'],
        'machine': {'cpu': sh("lscpu | sed -n 's/^Model name: *//p'"), 'nproc': sh('nproc'),
                    'mem': sh("free -g | awk 'NR==2 {print $2\" GB\"}'"), 'kernel': sh('uname -r')},
        'load_at_start': {'loadavg': sh('cat /proc/loadavg'),
                          'other_containers': sh("docker ps --format '{{.Names}}' | grep -v '^" + os.environ['STACK_NAME'] + "-' | sort | tr '\\n' ' '")},
    })
else:
    m['finished_at'] = time.strftime('%Y-%m-%dT%H:%M:%S%z')
    m['load_at_end'] = {'loadavg': sh('cat /proc/loadavg')}
    m['status'] = open(os.path.join(os.path.dirname(path), 'status')).read().strip()
json.dump(m, open(path, 'w'), indent=2, ensure_ascii=False)
PY
}

stack_up() { "${PROJECT_DIR}/scripts/stack.sh" up; }

build_jars() {
  local src="${RUN_DIR}/baseline-src"
  git -C "$PROJECT_DIR" worktree remove --force "$src" >/dev/null 2>&1 || true
  git -C "$PROJECT_DIR" worktree add --detach "$src" "$S_BASELINE_TAG"
  (cd "$src" && JAVA_HOME="${APP_JAVA_HOME:-${HOME}/.jdks/temurin-21.0.12.1}" mvn -q package -DskipTests)
  cp "$src"/target/local-deals-service-*-SNAPSHOT.jar "${RUN_DIR}/baseline.jar"
  (cd "$PROJECT_DIR" && JAVA_HOME="${APP_JAVA_HOME:-${HOME}/.jdks/temurin-21.0.12.1}" mvn -q package -DskipTests)
  cp "$PROJECT_DIR"/target/local-deals-service-*-SNAPSHOT.jar "${RUN_DIR}/current.jar"
}

# A fresh app for every measurement, fresh login tokens (they expire ~30 min after last use)
# and a warm-up that is not recorded in summary.csv.
start_build() { # which
  local which="$1"
  "${PROJECT_DIR}/scripts/stack.sh" app-stop
  # the app first: on a fresh stack its Flyway migrations create the tables the fixture fills
  APP_JAR="${RUN_DIR}/${which}.jar" APP_ARGS="$(build_args "$which")" "${PROJECT_DIR}/scripts/stack.sh" app-start
  env $(build_redis_env "$which") "$0" users "$S_USERS"
  # the warm-up needs the same k6 environment as the measured runs, or it only measures 403s
  BENCH_OUT="${S_RESULT}/raw/warmup" BENCH_COMMIT="$(build_commit "$which")" DURATION="$S_WARMUP_DURATION" \
    STOCK=1000 env "$(build_env "$which")" $(build_redis_env "$which") "$0" step "$S_WARMUP_RATE"
}
build_args() { [[ "$1" == baseline ]] && echo "$ARGS_BASELINE" || echo "$ARGS_CURRENT"; }
# Which Redis a build talks to, as environment for the fixture, the drills and the CPU column.
# A build from before M5 has untagged keys, so it can only run on the single node.
build_redis_env() { # which
  if [[ "$1" != baseline && -n "${S_BUCKETS:-}" ]]; then
    echo "SECKILL_BUCKETS=${S_BUCKETS} BENCH_REDIS_CLUSTER=${STACK_REDIS_CLUSTER_NODES:-}"
  else
    echo "SECKILL_BUCKETS=0 BENCH_REDIS_CLUSTER="
  fi
}
build_commit() { [[ "$1" == baseline ]] && echo "$S_BASELINE_COMMIT" || echo "$S_CURRENT_COMMIT"; }
# k6 signs a seckill token itself; only a build that predates the funnel does not want one.
build_env() { # which
  if [[ "$1" == current || "$S_BASELINE_FLAVOUR" == funnel ]]; then
    echo "SECKILL_TOKEN_SECRET=${M3_TOKEN_SECRET}"
  else
    echo "SECKILL_TOKEN_SECRET="
  fi
}

ladder() { # which
  start_build "$1"
  env "$(build_env "$1")" $(build_redis_env "$1") BENCH_OUT="$S_RESULT" BENCH_COMMIT="$(build_commit "$1")" \
    DURATION="$S_STEP_DURATION" STOCK="$S_STEP_STOCK" "$0" step $S_RATES
  "${PROJECT_DIR}/scripts/stack.sh" app-stop
}

drain_round() { # which
  start_build "$1"
  env "$(build_env "$1")" $(build_redis_env "$1") BENCH_OUT="$S_RESULT" BENCH_COMMIT="$(build_commit "$1")" \
    "$0" drain "$S_DRAIN_STOCK" "$S_DRAIN_RATE"
  "${PROJECT_DIR}/scripts/stack.sh" app-stop
}

# One drain with an explicit consume batch size and thread count. The commit column carries the
# combination (e.g. 1aad214:b64t16) so the sweep rows stay in the same summary.csv schema.
sweep_round() { # batch:threads
  local batch="${1%%:*}" threads="${1##*:}"
  local pool=$(( threads + 8 ))
  "${PROJECT_DIR}/scripts/stack.sh" app-stop
  APP_JAR="${RUN_DIR}/current.jar" \
    APP_ARGS="${ARGS_CURRENT} --local-deals.seckill.consume.batch-size=${batch} --local-deals.seckill.consume.thread-count=${threads} --spring.datasource.hikari.maximum-pool-size=${pool}" \
    "${PROJECT_DIR}/scripts/stack.sh" app-start
  "$0" users "$S_USERS"
  BENCH_OUT="${S_RESULT}/raw/warmup" BENCH_COMMIT="sweep" DURATION="$S_WARMUP_DURATION" \
    STOCK=1000 env "$(build_env current)" "$0" step "$S_WARMUP_RATE"
  env "$(build_env current)" BENCH_OUT="$S_RESULT" \
    BENCH_COMMIT="${S_CURRENT_COMMIT}:b${batch}t${threads}" \
    "$0" drain "$S_DRAIN_STOCK" "$S_DRAIN_RATE"
  "${PROJECT_DIR}/scripts/stack.sh" app-stop
}

# One drain of the current build with an explicit bucket count. The commit column carries it
# (e.g. 1aad214:k8) so the sweep rows keep the summary.csv schema.
bucket_sweep_round() { # bucket count
  local buckets="$1"
  "${PROJECT_DIR}/scripts/stack.sh" app-stop
  local args="${ARGS_CURRENT/--local-deals.seckill.bucket.count=${S_BUCKETS}/--local-deals.seckill.bucket.count=${buckets}}"
  APP_JAR="${RUN_DIR}/current.jar" APP_ARGS="$args" "${PROJECT_DIR}/scripts/stack.sh" app-start
  env SECKILL_BUCKETS="$buckets" BENCH_REDIS_CLUSTER="${STACK_REDIS_CLUSTER_NODES:-}" "$0" users "$S_USERS"
  BENCH_OUT="${S_RESULT}/raw/warmup" BENCH_COMMIT="sweep" DURATION="$S_WARMUP_DURATION" STOCK=1000 \
    env "$(build_env current)" SECKILL_BUCKETS="$buckets" BENCH_REDIS_CLUSTER="${STACK_REDIS_CLUSTER_NODES:-}" \
    "$0" step "$S_WARMUP_RATE"
  env "$(build_env current)" SECKILL_BUCKETS="$buckets" BENCH_REDIS_CLUSTER="${STACK_REDIS_CLUSTER_NODES:-}" \
    BENCH_OUT="$S_RESULT" BENCH_COMMIT="${S_CURRENT_COMMIT}:k${buckets}" \
    "$0" drain "$S_DRAIN_STOCK" "$S_DRAIN_RATE"
  "${PROJECT_DIR}/scripts/stack.sh" app-stop
}

wait_cluster_ok() {
  local deadline=$(( SECONDS + 180 ))
  until $STACK_REDIS CLUSTER INFO 2>/dev/null | grep -q '^cluster_state:ok'; do
    (( SECONDS < deadline )) || s_fail "redis cluster did not come back after the drill"
    sleep 2
  done
}

# Kill one Redis master mid-load: what the cluster loses, and that MySQL still does not oversell.
redis_kill_round() {
  start_build current
  local line
  line="$(env "$(build_env current)" $(build_redis_env current) BENCH_OUT="$S_RESULT" \
    APP_JAR="${RUN_DIR}/current.jar" APP_ARGS="${ARGS_CURRENT} ${S_DRILL_ARGS}" DRILL_TIMEOUT=1800 \
    "${PROJECT_DIR}/benchmark/v2/scripts/redis-kill-drill.sh" "$S_KILL_STOCK" "$S_KILL_RATE" "$S_KILL_AFTER" | tail -1)"
  [[ "$line" == voucher=* ]] || { echo "redis kill drill printed no result: ${line}"; return 1; }
  python3 - "${S_RESULT}/redis-kill-drill.csv" "$S_CURRENT_COMMIT" "$line" <<'PY'
import csv, os, sys
path, commit, line = sys.argv[1:]
row = {'commit': commit, **dict(kv.split('=', 1) for kv in line.split(','))}
new = not os.path.exists(path)
with open(path, 'a', newline='') as f:
    w = csv.DictWriter(f, fieldnames=list(row))
    if new:
        w.writeheader()
    w.writerow(row)
PY
  "${PROJECT_DIR}/scripts/stack.sh" app-stop
  # The cluster must be whole again before the next phase measures anything.
  wait_cluster_ok
}

kill_round() { # mode: kill | broker
  start_build current
  local line broker_down=""
  [[ "$1" == broker ]] && broker_down=1
  line="$(env "$(build_env current)" $(build_redis_env current) BENCH_OUT="$S_RESULT" APP_JAR="${RUN_DIR}/current.jar" \
    APP_ARGS="${ARGS_CURRENT} ${S_DRILL_ARGS}" BROKER_DOWN="$broker_down" DRILL_TIMEOUT=1800 \
    "${PROJECT_DIR}/benchmark/v2/scripts/kill-drill.sh" "$S_KILL_STOCK" "$S_KILL_RATE" "$S_KILL_AFTER" | tail -1)"
  [[ "$line" == voucher=* ]] || { echo "kill drill printed no result: ${line}"; return 1; }
  python3 - "${S_RESULT}/kill-drill.csv" "$S_CURRENT_COMMIT" "$1" "$line" <<'PY'
import csv, os, sys
path, commit, mode, line = sys.argv[1:]
row = {'commit': commit, 'mode': mode, **dict(kv.split('=', 1) for kv in line.split(','))}
new = not os.path.exists(path)
with open(path, 'a', newline='') as f:
    w = csv.DictWriter(f, fieldnames=list(row))
    if new:
        w.writeheader()
    w.writerow(row)
PY
  "${PROJECT_DIR}/scripts/stack.sh" app-stop
}

run_scenario() { # name
  cd "$PROJECT_DIR"
  export S_NAME="$1"
  load_scenario "$1"
  export S_MILESTONE S_BASELINE_TAG S_WARMUP_RATE S_WARMUP_DURATION S_RATES S_STEP_DURATION S_STEP_STOCK \
    S_DRAIN_ROUNDS S_DRAIN_STOCK S_DRAIN_RATE S_KILL_ROUNDS S_BROKER_KILL_ROUNDS S_KILL_STOCK S_KILL_RATE \
    S_KILL_AFTER S_EXTRA_ARGS S_USERS S_BASELINE_FLAVOUR STACK_NAME K6_IMAGE
  S_SWEEP="${S_SWEEP:-}"; export S_SWEEP
  S_BUCKETS="${S_BUCKETS:-}"; export S_BUCKETS
  S_BUCKET_SWEEP="${S_BUCKET_SWEEP:-}"; export S_BUCKET_SWEEP
  S_REDIS_KILL_ROUNDS="${S_REDIS_KILL_ROUNDS:-0}"; export S_REDIS_KILL_ROUNDS
  S_DRILL_ARGS="${S_DRILL_ARGS:-}"; export S_DRILL_ARGS
  export S_CURRENT_COMMIT="$(git -C "$PROJECT_DIR" rev-parse --short HEAD)"
  export S_BASELINE_COMMIT="$(git -C "$PROJECT_DIR" rev-parse --short "${S_BASELINE_TAG}^{commit}")"
  # Same pinning as M0 (see benchmark/v2/m0/baseline.md) unless overridden.
  export APP_CPUS="${APP_CPUS:-0-3,8-11}" DEPS_CPUS="${DEPS_CPUS:-4-5,12-13}" K6_CPUS="${K6_CPUS_SCENARIO:-6-7,14-15}"
  K6_CPUS="${K6_CPUS_SCENARIO:-6-7,14-15}"
  if [[ "$S_BASELINE_FLAVOUR" == funnel ]]; then
    # v2.0-m3 and later: no activity limit any more, and the same token secret as the current build
    export ARGS_BASELINE="${M3_LIMITS_CURRENT} --local-deals.order.pay-timeout=24h --local-deals.seckill.token.secret=${M3_TOKEN_SECRET}"
  else
    export ARGS_BASELINE="${M3_LIMITS_BASELINE} --local-deals.order.pay-timeout=24h"
  fi
  export ARGS_CURRENT="${M3_LIMITS_CURRENT} --local-deals.order.pay-timeout=24h --local-deals.seckill.token.secret=${M3_TOKEN_SECRET} ${S_EXTRA_ARGS}"
  if [[ -n "${S_BUCKETS:-}" ]]; then
    # The current build runs on the cluster with its buckets; the baseline predates both.
    export REDIS_MODE=cluster
    ARGS_CURRENT="${ARGS_CURRENT} --local-deals.seckill.bucket.count=${S_BUCKETS}"
  fi
  BROKER_STORE_ROOT_CHECK="$(sed -n 's/^LOCAL_DEALS_ROCKETMQ_STORE_ROOT=//p' "${PROJECT_DIR}/.env" 2>/dev/null | tail -1)/x"
  # Take the scenario lock before anything that touches the shared stack, and arm the cleanup
  # trap only once it is ours: a refused run must never tear down the running one's containers.
  mkdir -p "${PROJECT_DIR}/benchmark/v2/run"
  exec 9>"${PROJECT_DIR}/benchmark/v2/run/.scenario.lock"
  flock -n 9 || fail "preflight: another scenario is running"
  export S_RESULT="${PROJECT_DIR}/benchmark/v2/${S_MILESTONE}/$(date +%Y%m%d-%H%M%S)-$1"
  mkdir -p "${S_RESULT}/raw"
  echo RUNNING >"${S_RESULT}/status"
  : >"${S_RESULT}/run.log"
  trap scenario_cleanup EXIT
  preflight
  write_manifest start
  s_log "scenario $1 -> ${S_RESULT}"

  phase stack-up 900 stack_up
  phase build 1200 build_jars
  local rounds_timeout=$(( 600 + $(wc -w <<<"$S_RATES") * 180 ))
  phase ladder-baseline "$rounds_timeout" ladder baseline
  phase ladder-current "$rounds_timeout" ladder current
  local i
  for (( i = 1; i <= S_DRAIN_ROUNDS; i++ )); do
    phase "drain-baseline-${i}" 1500 drain_round baseline
    phase "drain-current-${i}" 1500 drain_round current
  done
  local combo
  for combo in $S_SWEEP; do phase "sweep-${combo/:/x}" 1500 sweep_round "$combo"; done
  local buckets
  for buckets in ${S_BUCKET_SWEEP:-}; do phase "bucket-sweep-k${buckets}" 1500 bucket_sweep_round "$buckets"; done
  for (( i = 1; i <= S_KILL_ROUNDS; i++ )); do phase "kill-drill-${i}" 2400 kill_round kill; done
  for (( i = 1; i <= S_BROKER_KILL_ROUNDS; i++ )); do phase "broker-kill-drill-${i}" 2700 kill_round broker; done
  for (( i = 1; i <= ${S_REDIS_KILL_ROUNDS:-0}; i++ )); do phase "redis-kill-drill-${i}" 2700 redis_kill_round; done
  [[ -s "${S_RESULT}/summary.csv" ]] || s_fail "result: summary.csv is empty"
  echo DONE >"${S_RESULT}/status"
  write_manifest end
  s_log "scenario $1 done -> ${S_RESULT}"
}

case "${1:-}" in
  users) python3 "$FIXTURE" users "${2:?count}"; rm -f "$USER_CURSOR_FILE" ;;
  step) shift; for rate in "$@"; do one_run step "$rate" "$STOCK" "$DURATION"; done ;;
  drain) one_run drain "${3:?rate}" "${2:?stock}" "$(( ${2} / ${3} + 1 ))s" ;;
  profile) profile "${2:?seconds}" "${3:?name}" ;;
  m3|m3-smoke|m4|m4-smoke|m5|m5-smoke) run_scenario "$1" ;;
  _phase) shift; "$@" ;;
  *) sed -n '2,20p' "$0"; exit 2 ;;
esac

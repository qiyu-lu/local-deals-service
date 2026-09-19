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
# Every run writes raw k6 JSON/logs under benchmark/v2/<MILESTONE>/raw/ (gitignored) and appends
# one row per run to benchmark/v2/<MILESTONE>/summary.csv. The commit column is HEAD unless
# BENCH_COMMIT names the build under test (e.g. a jar built from an older tag).
#
# CPU isolation: the app runs under taskset (APP_CPUS, see stack.sh), dependency containers are
# pinned with DEPS_CPUS, and k6 runs in a container pinned to K6_CPUS. CPU columns are cores used
# on average during the k6 window, from /proc/<pid>/stat and cgroup cpuacct.usage deltas.
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MILESTONE="${MILESTONE:-m0}"
OUT_DIR="${PROJECT_DIR}/benchmark/v2/${MILESTONE}"
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
  docker run --rm --network host --cpuset-cpus "$K6_CPUS" --user "$(id -u):$(id -g)" \
    -v "${PROJECT_DIR}/benchmark/v2/scripts:/scripts:ro" \
    -v "${PROJECT_DIR}/benchmark/v2/run:/data:ro" \
    -v "${RAW_DIR}:/out" \
    "$K6_IMAGE" run --quiet \
    -e BASE_URL="$STACK_APP" -e VOUCHER_ID="$voucher" -e RATE="$rate" -e DURATION="$duration" \
    -e TOKENS=/data/tokens.csv -e USER_OFFSET="$offset" \
    --summary-export "/out/${name}.json" /scripts/seckill.js >"${RAW_DIR}/${name}.log" 2>&1 || true
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
  voucher="$(python3 "$FIXTURE" voucher "$stock")"
  name="${kind}-r${rate}-s${stock}-$(date +%H%M%S)"
  pid="$(app_pid)"
  hz="$(getconf CLK_TCK)"
  for svc in mysql redis broker; do dep0[$svc]="$(container_ns "${STACK_NAME}-${svc}")"; done
  sample_orders "$voucher" "${RAW_DIR}/${name}-orders.csv" &
  local sampler=$!
  ticks0="$(proc_ticks "$pid")"; started="$(date +%s.%N)"
  run_k6 "$name" "$voucher" "$rate" "$duration"
  ticks1="$(proc_ticks "$pid")"; ended="$(date +%s.%N)"
  for svc in mysql redis broker; do dep1[$svc]="$(container_ns "${STACK_NAME}-${svc}")"; done
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
  python3 - "$SUMMARY" "${RAW_DIR}/${name}.json" "$kind" "$rate" "$stock" "$voucher" "$drain" \
    "$(python3 -c "print(round(($ticks1-$ticks0)/$hz/($ended-$started),2))")" \
    "$(python3 -c "print(round(($ended-$started),1))")" \
    "${dep0[mysql]}:${dep1[mysql]}" "${dep0[redis]}:${dep1[redis]}" "${dep0[broker]}:${dep1[broker]}" \
    "${BENCH_COMMIT:-$(git -C "$PROJECT_DIR" rev-parse --short HEAD)}" <<'PY'
import csv, json, os, sys
summary, raw, kind, rate, stock, voucher, drain, app_cpu, wall, mysql_ns, redis_ns, broker_ns, commit = sys.argv[1:]
samples = [tuple(float(x) for x in line.split(',')) for line in open(raw[:-5] + '-orders.csv') if ',' in line]
m = json.load(open(raw))['metrics']
wall = float(wall)
def cores(pair):
    a, b = (int(x) for x in pair.split(':'))
    return round((b - a) / 1e9 / wall, 2)
def val(metric, key, default=0):
    return m.get(metric, {}).get(key, default)
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

case "${1:-}" in
  users) python3 "$FIXTURE" users "${2:?count}"; rm -f "$USER_CURSOR_FILE" ;;
  step) shift; for rate in "$@"; do one_run step "$rate" "$STOCK" "$DURATION"; done ;;
  drain) one_run drain "${3:?rate}" "${2:?stock}" "$(( ${2} / ${3} + 1 ))s" ;;
  profile) profile "${2:?seconds}" "${3:?name}" ;;
  *) sed -n '2,16p' "$0"; exit 2 ;;
esac

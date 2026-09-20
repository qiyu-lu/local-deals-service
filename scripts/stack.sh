#!/usr/bin/env bash
# Isolated MySQL/Redis/RocketMQ/ES stack + application launcher for integration tests and
# benchmarks. It never touches the dev stack (fixed ports 3306/6379/9876/10911/9200) and only
# removes resources of its own compose project.
#
#   scripts/stack.sh up                 start dependencies (named volumes, 127.0.0.1 ports)
#   scripts/stack.sh env                print the variables the app/tests need (eval-able)
#   scripts/stack.sh it 'Seckill*IT'    run tests against this stack
#   scripts/stack.sh build              package the app jar with APP_JAVA_HOME (Java 21)
#   scripts/stack.sh app-start|app-stop start/stop the jar (pinned with taskset when APP_CPUS set;
#                                       APP_JAR runs another build, e.g. an older tag)
#   scripts/stack.sh pin                pin dependency containers to DEPS_CPUS
#
#   INSTANCE=n                          run the n-th application instance: ports APP_PORT+(n-1)
#                                       and MANAGEMENT_PORT+(n-1), its own pid and log file, and
#                                       its own APP_CPUS. Unset means instance 1, exactly as
#                                       before, and app-stop without it stops every instance.
#   scripts/stack.sh lb-start|lb-stop   nginx on LB_PORT in front of APP_INSTANCES instances:
#                                       round robin, keepalive upstreams, and X-Trace-Id from
#                                       $request_id when the caller sent none
#
#   REDIS_MODE=cluster                  run Redis as 3 masters + 3 replicas (ports 2700x/3700x)
#                                       instead of the single node; 'env' then exports
#                                       SPRING_DATA_REDIS_CLUSTER_NODES for tests and the app.
#   scripts/stack.sh status|down
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ACTION="${1:-}"

STACK_ID="${STACK_ID:-v2}"
STACK_NAME="ld-${STACK_ID}"
MYSQL_PORT="${MYSQL_PORT:-23306}"
REDIS_PORT="${REDIS_PORT:-26379}"
NAMESRV_PORT="${NAMESRV_PORT:-29876}"
BROKER_PORT="${BROKER_PORT:-20911}"
ES_PORT="${ES_PORT:-29200}"
REDIS_MODE="${REDIS_MODE:-single}"
REDIS_CLUSTER_PORT_BASE="${REDIS_CLUSTER_PORT_BASE:-2700}"
REDIS_CLUSTER_BUS_BASE="${REDIS_CLUSTER_BUS_BASE:-3700}"
APP_PORT="${APP_PORT:-28083}"
MANAGEMENT_PORT="${MANAGEMENT_PORT:-28184}"
# Which of the instances this invocation is about, and how many nginx balances over. One
# instance keeps the pre-M8 ports, pid file and log name, so every earlier scenario is unchanged.
INSTANCE_GIVEN="${INSTANCE:-}"
INSTANCE="${INSTANCE:-1}"
APP_INSTANCES="${APP_INSTANCES:-1}"
LB_PORT="${LB_PORT:-$((APP_PORT + 900))}"
LB_ACCESS_LOG="${LB_ACCESS_LOG:-}"
STACK_SUBNET="${STACK_SUBNET:-172.30.56.0/24}"
NGINX_IMAGE="${NGINX_IMAGE:-nginx:1.22}"
MYSQL_PASSWORD="${STACK_MYSQL_PASSWORD:-ld-stack-mysql}"
REDIS_PASSWORD="${STACK_REDIS_PASSWORD:-ld-stack-redis}"
SCHEMA="${STACK_SCHEMA:-local_deals}"
RUN_DIR="${RUN_DIR:-${PROJECT_DIR}/benchmark/v2/run/${STACK_ID}}"
# JDK for build, tests and the app; point it at another JDK to rerun an older tag.
APP_JAVA_HOME="${APP_JAVA_HOME:-${HOME}/.jdks/temurin-21.0.12.1}"
APP_CPUS="${APP_CPUS:-}"
DEPS_CPUS="${DEPS_CPUS:-}"
APP_JAVA_OPTS="${APP_JAVA_OPTS:--Xms2g -Xmx2g}"
# Broker store root on a disk with free space; read from the environment or the local .env.
# Each stack gets <root>/<stack-name>; unset -> a named docker volume.
ROCKETMQ_STORE_ROOT="${LOCAL_DEALS_ROCKETMQ_STORE_ROOT:-$(sed -n 's/^LOCAL_DEALS_ROCKETMQ_STORE_ROOT=//p' "${PROJECT_DIR}/.env" 2>/dev/null | tail -1)}"
BROKER_STORE=rocketmq-store
[[ -z "$ROCKETMQ_STORE_ROOT" ]] || BROKER_STORE="${ROCKETMQ_STORE_ROOT}/${STACK_NAME}"
# Same for Elasticsearch: 8.x stops allocating shards above the 90% high disk watermark.
ES_DATA_ROOT="${LOCAL_DEALS_ES_DATA_ROOT:-$(sed -n 's/^LOCAL_DEALS_ES_DATA_ROOT=//p' "${PROJECT_DIR}/.env" 2>/dev/null | tail -1)}"
ES_DATA=es-data
[[ -z "$ES_DATA_ROOT" ]] || ES_DATA="${ES_DATA_ROOT}/${STACK_NAME}"
FORBIDDEN_PORTS=(3306 6379 9876 10911 9200 8083 8088)

fail() { echo "stack: $*" >&2; exit 1; }

# Instance n listens one port up from instance n-1, on both the API and the management port.
instance_app_port() { echo $(( APP_PORT + ${1:-$INSTANCE} - 1 )); }
instance_management_port() { echo $(( MANAGEMENT_PORT + ${1:-$INSTANCE} - 1 )); }
# Instance 1 keeps app.pid and app.log, so every scenario written before M8 still finds them.
instance_suffix() { [[ "${1:-$INSTANCE}" == 1 ]] && echo "" || echo "-i${1:-$INSTANCE}"; }
instance_pid_file() { echo "${RUN_DIR}/app$(instance_suffix "${1:-$INSTANCE}").pid"; }
instance_log_file() { echo "${RUN_DIR}/app$(instance_suffix "${1:-$INSTANCE}").log"; }
# Which instances have a pid file right now, in order.
running_instances() {
  local n
  for (( n = 1; n <= 32; n++ )); do
    [[ -f "$(instance_pid_file "$n")" ]] && echo "$n"
  done
  return 0
}

check_isolation() {
  [[ "$STACK_ID" =~ ^[a-z0-9][a-z0-9-]{1,30}$ ]] || fail "STACK_ID must match [a-z0-9-]{2,31}"
  local port forbidden
  local cluster_ports=()
  # Both the client and the bus ports: another stack's bus range is just as fatal a collision.
  if [[ "$REDIS_MODE" == cluster ]]; then
    check_cluster_bus_base
    mapfile -t cluster_ports < <(redis_cluster_ports; redis_cluster_bus_ports)
  fi
  local instance_ports=() n
  for (( n = 1; n <= APP_INSTANCES; n++ )); do
    instance_ports+=("$(instance_app_port "$n")" "$(instance_management_port "$n")")
  done
  for port in "$MYSQL_PORT" "$REDIS_PORT" "$NAMESRV_PORT" "$BROKER_PORT" "$ES_PORT" "$APP_PORT" \
      "$MANAGEMENT_PORT" "$LB_PORT" "${instance_ports[@]}" ${cluster_ports[@]+"${cluster_ports[@]}"}; do
    for forbidden in "${FORBIDDEN_PORTS[@]}"; do
      [[ "$port" != "$forbidden" ]] || fail "port ${port} belongs to the dev stack"
    done
  done
}

# Redis 6.2 has no cluster-port: a node's bus always listens on its client port + 10000, and the
# compose only *announces* the bus port. Announce anything else and the nodes gossip to a port
# nobody is listening on — the cluster never forms, and `--cluster create` waits for a handshake
# that cannot arrive until something times out fifteen minutes later. The ports are concatenated
# with the node index, so the bases have to differ by exactly 1000.
check_cluster_bus_base() {
  (( REDIS_CLUSTER_BUS_BASE == REDIS_CLUSTER_PORT_BASE + 1000 )) ||
    fail "REDIS_CLUSTER_BUS_BASE must be REDIS_CLUSTER_PORT_BASE + 1000 (Redis 6.2 binds the bus at port+10000); got ${REDIS_CLUSTER_PORT_BASE} and ${REDIS_CLUSTER_BUS_BASE}"
}

redis_cluster_ports() { # 1..6
  local i
  for i in 1 2 3 4 5 6; do echo "${REDIS_CLUSTER_PORT_BASE}${i}"; done
}

redis_cluster_bus_ports() { # 1..6
  local i
  for i in 1 2 3 4 5 6; do echo "${REDIS_CLUSTER_BUS_BASE}${i}"; done
}

redis_cluster_nodes() {
  local port list=""
  for port in $(redis_cluster_ports); do list="${list}${list:+,}127.0.0.1:${port}"; done
  echo "$list"
}

cluster_compose() {
  env STACK_NAME="$STACK_NAME" STACK_RESTART=no \
    LOCAL_DEALS_REDIS_PASSWORD="$REDIS_PASSWORD" \
    REDIS_CLUSTER_PORT_BASE="$REDIS_CLUSTER_PORT_BASE" REDIS_CLUSTER_BUS_BASE="$REDIS_CLUSTER_BUS_BASE" \
    docker compose --project-name "${STACK_NAME}-redis-cluster" --project-directory "$PROJECT_DIR" \
    --env-file /dev/null --file "${PROJECT_DIR}/docker/redis-cluster/docker-compose.yml" "$@"
}

cluster_cli() { # port, then redis-cli arguments
  local port="$1"; shift
  redis-cli -h 127.0.0.1 -p "$port" -a "$REDIS_PASSWORD" --no-auth-warning "$@"
}

cluster_node_ready() { cluster_cli "$1" PING | grep -qx PONG; }
cluster_state_ok() { cluster_cli "${REDIS_CLUSTER_PORT_BASE}1" CLUSTER INFO | grep -q '^cluster_state:ok'; }

start_redis_cluster() {
  # The nodes share the host network, so a busy port is a hard failure the container reports
  # only in its log. Say it here instead of timing out on a node that never started.
  local port
  for port in $(redis_cluster_ports) $(redis_cluster_bus_ports); do
    ss -ltn "sport = :${port}" 2>/dev/null | grep -q LISTEN &&
      fail "port ${port} is already in use; another cluster is running (REDIS_CLUSTER_PORT_BASE/REDIS_CLUSTER_BUS_BASE)"
  done
  cluster_compose up -d
  local port
  for port in $(redis_cluster_ports); do
    wait_for "Redis node ${port}" cluster_node_ready "$port"
  done
  if cluster_state_ok; then
    echo "redis cluster already formed"
    return 0
  fi
  local addresses=()
  for port in $(redis_cluster_ports); do addresses+=("127.0.0.1:${port}"); done
  redis-cli -a "$REDIS_PASSWORD" --no-auth-warning --cluster create "${addresses[@]}" \
    --cluster-replicas 1 --cluster-yes >/dev/null
  wait_for "redis cluster state" cluster_state_ok
}

compose() {
  env STACK_NAME="$STACK_NAME" STACK_RESTART=no STACK_BIND=127.0.0.1 STACK_SUBNET="$STACK_SUBNET" \
    MYSQL_PORT="$MYSQL_PORT" REDIS_PORT="$REDIS_PORT" NAMESRV_PORT="$NAMESRV_PORT" \
    BROKER_PORT="$BROKER_PORT" ES_PORT="$ES_PORT" \
    MYSQL_ROOT_PASSWORD="$MYSQL_PASSWORD" MYSQL_DATABASE="$SCHEMA" \
    LOCAL_DEALS_REDIS_PASSWORD="$REDIS_PASSWORD" \
    MYSQL_VOLUME=mysql-data REDIS_VOLUME=redis-data ES_VOLUME="$ES_DATA" BROKER_STORE="$BROKER_STORE" \
    docker compose --project-name "$STACK_NAME" --project-directory "$PROJECT_DIR" \
    --env-file /dev/null --file "${PROJECT_DIR}/docker-compose.yml" "$@"
}

datasource_url() {
  printf 'jdbc:mysql://127.0.0.1:%s/%s?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&createDatabaseIfNotExist=true' \
    "$MYSQL_PORT" "$SCHEMA"
}

print_env() {
  cat <<EOF
export LOCAL_DEALS_DATASOURCE_URL='$(datasource_url)'
export LOCAL_DEALS_DATASOURCE_USERNAME=root
export LOCAL_DEALS_DATASOURCE_PASSWORD='${MYSQL_PASSWORD}'
export LOCAL_DEALS_REDIS_HOST=127.0.0.1
export LOCAL_DEALS_REDIS_PORT=${REDIS_PORT}
export LOCAL_DEALS_REDIS_PASSWORD='${REDIS_PASSWORD}'
export ROCKETMQ_NAMESERVER=127.0.0.1:${NAMESRV_PORT}
export SPRING_ELASTICSEARCH_URIS=http://127.0.0.1:${ES_PORT}
export STACK_MYSQL="mysql -h127.0.0.1 -P${MYSQL_PORT} -uroot -p${MYSQL_PASSWORD} ${SCHEMA}"
export STACK_REDIS="redis-cli -h 127.0.0.1 -p ${REDIS_PORT} -a ${REDIS_PASSWORD} --no-auth-warning"
export STACK_APP=http://127.0.0.1:$(instance_app_port)
export STACK_MANAGEMENT=http://127.0.0.1:$(instance_management_port)
export STACK_LB=http://127.0.0.1:${LB_PORT}
export STACK_APP_INSTANCES=${APP_INSTANCES}
EOF
  # Cluster additions last, so STACK_REDIS ends up pointing at the cluster. The node list gets a
  # neutral name: whether a process should talk to the cluster is its caller's decision (tests
  # do; a build from before the buckets cannot).
  if [[ "$REDIS_MODE" == cluster ]]; then
    cat <<EOF
export STACK_REDIS_CLUSTER_NODES='$(redis_cluster_nodes)'
export STACK_REDIS="redis-cli -c -h 127.0.0.1 -p ${REDIS_CLUSTER_PORT_BASE}1 -a ${REDIS_PASSWORD} --no-auth-warning"
EOF
  fi
}

wait_for() {
  local what="$1"; shift
  for _ in $(seq 1 90); do
    if "$@" >/dev/null 2>&1; then return 0; fi
    sleep 2
  done
  fail "timed out waiting for ${what}"
}

mysql_ready() { mysql -h127.0.0.1 -P"$MYSQL_PORT" -uroot -p"$MYSQL_PASSWORD" -e 'SELECT 1'; }
redis_ready() { redis-cli -h 127.0.0.1 -p "$REDIS_PORT" -a "$REDIS_PASSWORD" --no-auth-warning PING | grep -qx PONG; }
es_ready() { curl -fs "http://127.0.0.1:${ES_PORT}/_cluster/health"; }
rmq_ready() { docker exec "${STACK_NAME}-broker" sh mqadmin clusterList -n namesrv:9876 | grep -q "$STACK_NAME"; }

prepare_broker_store() {
  [[ "$BROKER_STORE" == /* ]] || return 0
  mkdir -p "$BROKER_STORE"
  # the broker runs as uid 3000 inside the image
  docker run --rm -v "${BROKER_STORE}:/store" --entrypoint chown apache/rocketmq:5.3.2 -R 3000:3000 /store 2>/dev/null ||
    docker run --rm -u 0 -v "${BROKER_STORE}:/store" --entrypoint chown apache/rocketmq:5.3.2 -R 3000:3000 /store
}

prepare_es_data() {
  [[ "$ES_DATA" == /* ]] || return 0
  mkdir -p "$ES_DATA"
  # the node runs as uid 1000 (gid 0) inside the image
  docker run --rm -u 0 -v "${ES_DATA}:/data" --entrypoint chown alpine:3 -R 1000:0 /data
}

# Docker sometimes refuses to attach a container to a bridge network it has just created:
# "failed to add interface vethXXXX to sandbox: check bridge port state: bridge port not
# forwarding after 200ms". A suite tears the network down and builds it again for every
# scenario, so it meets this race often — it cost the M8 overnight run its first scenario 38
# seconds in. The call is idempotent, and the retry has always succeeded.
compose_up() {
  local attempt
  for attempt in 1 2 3; do
    if compose up -d "$@"; then
      return 0
    fi
    echo "stack: compose up failed (attempt ${attempt}/3); retrying in 5s" >&2
    sleep 5
  done
  fail "compose up did not succeed after three attempts"
}

up() {
  check_isolation
  prepare_broker_store
  prepare_es_data
  # Cluster mode keeps the single node running as well: a benchmark compares a build that
  # predates the buckets, which cannot run on a cluster, against one that does.
  compose_up mysql redis namesrv broker elasticsearch
  wait_for Redis redis_ready
  [[ "$REDIS_MODE" != cluster ]] || start_redis_cluster
  wait_for MySQL mysql_ready
  wait_for Elasticsearch es_ready
  wait_for RocketMQ rmq_ready
  local topic group
  for topic in seckill-order-topic mysql-sync-topic order-close-topic; do
    docker exec "${STACK_NAME}-broker" sh mqadmin updateTopic -n namesrv:9876 -c "$STACK_NAME" -t "$topic" >/dev/null
  done
  for group in seckill-consumer-group es-sync-consumer-group order-close-consumer-group; do
    docker exec "${STACK_NAME}-broker" sh mqadmin updateSubGroup -n namesrv:9876 -c "$STACK_NAME" -g "$group" >/dev/null
  done
  [[ -z "$DEPS_CPUS" ]] || pin
  local redis_where="redis=${REDIS_PORT}"
  [[ "$REDIS_MODE" != cluster ]] || redis_where="redis-cluster=$(redis_cluster_nodes)"
  echo "stack ${STACK_NAME} ready: mysql=${MYSQL_PORT} ${redis_where} namesrv=${NAMESRV_PORT} broker=${BROKER_PORT} es=${ES_PORT}"
}

pin() {
  [[ -n "$DEPS_CPUS" ]] || fail "DEPS_CPUS is empty"
  local service
  for service in mysql redis namesrv broker es; do
    docker update --cpuset-cpus "$DEPS_CPUS" "${STACK_NAME}-${service}" >/dev/null
  done
  echo "dependency containers pinned to CPUs ${DEPS_CPUS}"
}

down() {
  check_isolation
  lb_stop
  app_stop
  compose down --volumes --remove-orphans
  cluster_compose down --volumes --remove-orphans
  if [[ "$BROKER_STORE" == /* && -d "$BROKER_STORE" ]]; then
    docker run --rm -u 0 -v "$(dirname "$BROKER_STORE"):/root-store" --entrypoint rm apache/rocketmq:5.3.2 \
      -rf "/root-store/$(basename "$BROKER_STORE")"
  fi
  if [[ "$ES_DATA" == /* && -d "$ES_DATA" ]]; then
    docker run --rm -u 0 -v "$(dirname "$ES_DATA"):/root-data" --entrypoint rm alpine:3 \
      -rf "/root-data/$(basename "$ES_DATA")"
  fi
}

status() {
  compose ps
  [[ "$REDIS_MODE" != cluster ]] || cluster_compose ps
  local n running=0
  for n in $(running_instances); do
    if kill -0 "$(<"$(instance_pid_file "$n")")" 2>/dev/null; then
      running=1
      echo "app instance ${n} running pid=$(<"$(instance_pid_file "$n")") port=$(instance_app_port "$n")"
    fi
  done
  (( running == 1 )) || echo "app not running"
  docker ps --filter "name=^/$(lb_container)$" --format 'nginx running port={{.Ports}}' | head -1
}

run_tests() {
  check_isolation
  local pattern="${2:?usage: stack.sh it <surefire -Dtest pattern>}"
  eval "$(print_env)"
  # Tests always follow the mode the stack was started in.
  if [[ "$REDIS_MODE" == cluster ]]; then
    export SPRING_DATA_REDIS_CLUSTER_NODES="$STACK_REDIS_CLUSTER_NODES"
    export SPRING_DATA_REDIS_CLUSTER_MAX_REDIRECTS=5
  fi
  (cd "$PROJECT_DIR" && JAVA_HOME="$APP_JAVA_HOME" mvn -o -q test -Dtest="$pattern" -DfailIfNoTests=false)
}

build() {
  [[ -x "${APP_JAVA_HOME}/bin/java" ]] || fail "APP_JAVA_HOME has no java: ${APP_JAVA_HOME}"
  (cd "$PROJECT_DIR" && JAVA_HOME="$APP_JAVA_HOME" mvn -q package -DskipTests)
}

app_jar() {
  local jar
  if [[ -n "${APP_JAR:-}" ]]; then
    [[ -f "$APP_JAR" ]] || fail "APP_JAR does not exist: ${APP_JAR}"
    echo "$APP_JAR"; return
  fi
  for jar in "${PROJECT_DIR}"/target/*.jar; do
    [[ -f "$jar" && "$jar" != *-sources.jar && "$jar" != *.original ]] && { echo "$jar"; return; }
  done
  fail "no jar under target/; run: scripts/stack.sh build"
}

app_start() {
  check_isolation
  mkdir -p "$RUN_DIR"
  local pid_file log_file port management
  pid_file="$(instance_pid_file)"; log_file="$(instance_log_file)"
  port="$(instance_app_port)"; management="$(instance_management_port)"
  [[ ! -f "$pid_file" ]] || ! kill -0 "$(<"$pid_file")" 2>/dev/null ||
    fail "app instance ${INSTANCE} already running pid=$(<"$pid_file")"
  local jar pin_cmd=()
  jar="$(app_jar)"
  [[ -z "$APP_CPUS" ]] || pin_cmd=(taskset -c "$APP_CPUS")
  eval "$(print_env)"
  # The instance number is the one thing a log line cannot work out for itself, and with three
  # of them writing about the same order it is the first thing anyone wants to know.
  export LOCAL_DEALS_INSTANCE="${LOCAL_DEALS_INSTANCE:-app-${INSTANCE}}"
  # A benchmark starts a fresh app for every measurement, so truncating app.log here used to
  # leave only the last one. Rotate instead: bench.sh archives all of them with the results.
  [[ ! -f "$log_file" ]] || mv "$log_file" "${log_file%.log}-$(date +%H%M%S-%N).log"
  # shellcheck disable=SC2086
  nohup "${pin_cmd[@]}" "${APP_JAVA_HOME}/bin/java" $APP_JAVA_OPTS ${APP_EXTRA_JAVA_OPTS:-} -jar "$jar" \
    --server.port="$port" \
    --management.server.port="$management" \
    ${APP_ARGS:-} >"$log_file" 2>&1 &
  echo "$!" >"$pid_file"
  wait_for "application readiness" curl -fs "http://127.0.0.1:${management}/actuator/health/readiness"
  echo "app started instance=${INSTANCE} pid=$(<"$pid_file") port=${port} cpus=${APP_CPUS:-all} log=${log_file}"
}

# Start APP_INSTANCES of them. APP_CPUS_PER_INSTANCE, when set, is a space-separated list of
# cpusets, one per instance: the scaling ladder needs each instance to own cores rather than
# share one budget, or it measures process overhead instead of scaling.
app_start_all() {
  local n sets=(${APP_CPUS_PER_INSTANCE:-})
  for (( n = 1; n <= APP_INSTANCES; n++ )); do
    INSTANCE="$n" APP_CPUS="${sets[n-1]:-$APP_CPUS}" app_start
  done
}

# A port nobody is listening on any more. A dead PID is not the same thing: a benchmark that
# restarts the app a dozen times hit "address already in use" six seconds after "app stopped".
port_free() { # port
  ! ss -ltn "sport = :$1" 2>/dev/null | grep -q LISTEN
}

lb_container() { echo "${STACK_NAME}-nginx"; }

# The edge the M8 scenarios drive: round robin over APP_INSTANCES, keepalive to the upstreams so
# the measurement is not a TIME_WAIT benchmark, and X-Trace-Id minted here when the caller sent
# none — which makes nginx's own log and the application's log share one id.
#
# No limit_req. The architecture has one, but a rate limit in front of a load test measures the
# rate limit; the funnel under test is the application's.
write_lb_config() {
  local conf="${RUN_DIR}/nginx.conf" n workers access
  workers="$(tr ',' '\n' <<<"${LB_CPUS:-0,1}" | awk -F- '{ n += ($2 == "" ? 1 : $2 - $1 + 1) } END { print (n < 1 ? 1 : n) }')"
  if [[ -n "$LB_ACCESS_LOG" ]]; then
    # Which instance served which trace: the multi-instance evidence, buffered so writing it
    # does not become the thing being measured.
    access='    log_format upstream_trace "$trace_id $upstream_addr $status $request_time";
    access_log /var/log/nginx/access.log upstream_trace buffer=256k flush=2s;'
  else
    access='    access_log off;'
  fi
  {
    echo "worker_processes ${workers};"
    echo "worker_rlimit_nofile 65535;"
    echo "error_log /dev/stderr warn;"
    echo "events { worker_connections 20480; multi_accept on; }"
    echo "http {"
    echo "    map \$http_x_trace_id \$trace_id { default \$http_x_trace_id; \"\" \$request_id; }"
    echo "$access"
    echo "    upstream app {"
    for (( n = 1; n <= APP_INSTANCES; n++ )); do
      echo "        server 127.0.0.1:$(instance_app_port "$n") max_fails=0;"
    done
    echo "        keepalive 512;"
    echo "    }"
    echo "    server {"
    echo "        listen ${LB_PORT} reuseport backlog=16384;"
    echo "        keepalive_requests 100000;"
    echo "        keepalive_timeout 120s;"
    echo "        location / {"
    echo "            proxy_pass http://app;"
    echo "            proxy_http_version 1.1;"
    echo "            proxy_set_header Connection \"\";"
    echo "            proxy_set_header X-Trace-Id \$trace_id;"
    echo "            proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;"
    echo "            proxy_set_header Host \$host;"
    echo "            proxy_connect_timeout 2s;"
    echo "            proxy_read_timeout 30s;"
    echo "        }"
    echo "    }"
    echo "}"
  } >"$conf"
  echo "$conf"
}

lb_start() {
  check_isolation
  mkdir -p "$RUN_DIR"
  lb_stop
  local conf
  conf="$(write_lb_config)"
  # A previous round's access log would answer the next round's questions.
  : >"${RUN_DIR}/access.log"
  port_free "$LB_PORT" || fail "port ${LB_PORT} is already in use"
  # Host networking: the upstreams are host processes on 127.0.0.1, and a bridge hop would add
  # a NAT layer to every request of the measurement.
  docker run -d --name "$(lb_container)" --network host \
    ${LB_CPUS:+--cpuset-cpus "$LB_CPUS"} \
    -v "${conf}:/etc/nginx/nginx.conf:ro" \
    -v "${RUN_DIR}:/var/log/nginx" \
    "$NGINX_IMAGE" >/dev/null
  local deadline=$(( SECONDS + 60 ))
  until curl -fs -o /dev/null "http://127.0.0.1:${LB_PORT}/actuator/health" 2>/dev/null ||
        curl -s -o /dev/null "http://127.0.0.1:${LB_PORT}/" 2>/dev/null; do
    (( SECONDS < deadline )) || fail "nginx did not answer on ${LB_PORT}; $(docker logs --tail 20 "$(lb_container)" 2>&1)"
    sleep 1
  done
  echo "nginx started port=${LB_PORT} upstreams=${APP_INSTANCES} cpus=${LB_CPUS:-all}"
}

lb_stop() {
  docker rm -f "$(lb_container)" >/dev/null 2>&1 || true
  for _ in $(seq 1 30); do port_free "$LB_PORT" && break; sleep 1; done
}

# Without INSTANCE_GIVEN this stops every instance that left a pid file behind, which is what
# `down` and every scenario's cleanup want; with it, only that one.
app_stop() {
  local targets=()
  if [[ -n "${INSTANCE_GIVEN:-}" ]]; then
    targets=("$INSTANCE")
  else
    mapfile -t targets < <(running_instances)
    # Nothing is running, but the ports still have to be free for the next start.
    [[ ${#targets[@]} -gt 0 ]] || targets=(1)
  fi
  local n had_pid=0
  for n in "${targets[@]}"; do
    local pid_file port management
    pid_file="$(instance_pid_file "$n")"
    port="$(instance_app_port "$n")"; management="$(instance_management_port "$n")"
    if [[ -f "$pid_file" ]]; then
      had_pid=1
      local pid
      pid="$(<"$pid_file")"
      if kill -0 "$pid" 2>/dev/null; then
        kill "$pid"
        for _ in $(seq 1 30); do kill -0 "$pid" 2>/dev/null || break; sleep 1; done
        kill -0 "$pid" 2>/dev/null && kill -9 "$pid"
      fi
      rm -f "$pid_file"
    fi
    # Whether or not we had a PID to kill: the next start needs these two ports, so wait for
    # them rather than letting Tomcat discover the problem and take the whole phase down.
    local port_to_wait
    for port_to_wait in "$port" "$management"; do
      for _ in $(seq 1 60); do port_free "$port_to_wait" && break; sleep 1; done
      port_free "$port_to_wait" || fail "port ${port_to_wait} is still in use after stopping the app"
    done
  done
  (( had_pid == 0 )) || echo "app stopped (instances: ${targets[*]})"
}

case "$ACTION" in
  up) up ;;
  down) down ;;
  status) status ;;
  env) print_env ;;
  pin) pin ;;
  it) run_tests "$@" ;;
  build) build ;;
  app-start) app_start ;;
  app-start-all) app_start_all ;;
  app-stop) app_stop ;;
  lb-start) lb_start ;;
  lb-stop) lb_stop ;;
  *) echo "usage: $0 {up|down|status|env|pin|it <pattern>|build|app-start|app-start-all|app-stop|lb-start|lb-stop}" >&2; exit 2 ;;
esac

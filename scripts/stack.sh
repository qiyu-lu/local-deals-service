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
STACK_SUBNET="${STACK_SUBNET:-172.30.56.0/24}"
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

check_isolation() {
  [[ "$STACK_ID" =~ ^[a-z0-9][a-z0-9-]{1,30}$ ]] || fail "STACK_ID must match [a-z0-9-]{2,31}"
  local port forbidden
  local cluster_ports=()
  # Both the client and the bus ports: another stack's bus range is just as fatal a collision.
  [[ "$REDIS_MODE" != cluster ]] || mapfile -t cluster_ports < <(redis_cluster_ports; redis_cluster_bus_ports)
  for port in "$MYSQL_PORT" "$REDIS_PORT" "$NAMESRV_PORT" "$BROKER_PORT" "$ES_PORT" "$APP_PORT" \
      "$MANAGEMENT_PORT" ${cluster_ports[@]+"${cluster_ports[@]}"}; do
    for forbidden in "${FORBIDDEN_PORTS[@]}"; do
      [[ "$port" != "$forbidden" ]] || fail "port ${port} belongs to the dev stack"
    done
  done
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
  if [[ "$REDIS_MODE" == cluster ]]; then
    # A neutral name: whether a given process should talk to the cluster is decided by its
    # caller (tests do, a pre-bucket build under benchmark does not).
    cat <<EOF
export STACK_REDIS_CLUSTER_NODES='$(redis_cluster_nodes)'
export STACK_REDIS="redis-cli -c -h 127.0.0.1 -p ${REDIS_CLUSTER_PORT_BASE}1 -a ${REDIS_PASSWORD} --no-auth-warning"
EOF
  fi
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
export STACK_APP=http://127.0.0.1:${APP_PORT}
export STACK_MANAGEMENT=http://127.0.0.1:${MANAGEMENT_PORT}
EOF
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

up() {
  check_isolation
  prepare_broker_store
  prepare_es_data
  # Cluster mode keeps the single node running as well: a benchmark compares a build that
  # predates the buckets, which cannot run on a cluster, against one that does.
  compose up -d mysql redis namesrv broker elasticsearch
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
  if [[ -f "${RUN_DIR}/app.pid" ]] && kill -0 "$(<"${RUN_DIR}/app.pid")" 2>/dev/null; then
    echo "app running pid=$(<"${RUN_DIR}/app.pid") port=${APP_PORT}"
  else
    echo "app not running"
  fi
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
  [[ ! -f "${RUN_DIR}/app.pid" ]] || ! kill -0 "$(<"${RUN_DIR}/app.pid")" 2>/dev/null ||
    fail "app already running pid=$(<"${RUN_DIR}/app.pid")"
  local jar pin_cmd=()
  jar="$(app_jar)"
  [[ -z "$APP_CPUS" ]] || pin_cmd=(taskset -c "$APP_CPUS")
  eval "$(print_env)"
  # shellcheck disable=SC2086
  nohup "${pin_cmd[@]}" "${APP_JAVA_HOME}/bin/java" $APP_JAVA_OPTS ${APP_EXTRA_JAVA_OPTS:-} -jar "$jar" \
    --server.port="$APP_PORT" \
    --management.server.port="$MANAGEMENT_PORT" \
    ${APP_ARGS:-} >"${RUN_DIR}/app.log" 2>&1 &
  echo "$!" >"${RUN_DIR}/app.pid"
  wait_for "application readiness" curl -fs "http://127.0.0.1:${MANAGEMENT_PORT}/actuator/health/readiness"
  echo "app started pid=$(<"${RUN_DIR}/app.pid") cpus=${APP_CPUS:-all} log=${RUN_DIR}/app.log"
}

app_stop() {
  [[ -f "${RUN_DIR}/app.pid" ]] || return 0
  local pid
  pid="$(<"${RUN_DIR}/app.pid")"
  if kill -0 "$pid" 2>/dev/null; then
    kill "$pid"
    for _ in $(seq 1 30); do kill -0 "$pid" 2>/dev/null || break; sleep 1; done
    kill -0 "$pid" 2>/dev/null && kill -9 "$pid"
  fi
  rm -f "${RUN_DIR}/app.pid"
  echo "app stopped"
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
  app-stop) app_stop ;;
  *) echo "usage: $0 {up|down|status|env|pin|it <pattern>|build|app-start|app-stop}" >&2; exit 2 ;;
esac

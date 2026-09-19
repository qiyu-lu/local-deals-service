#!/usr/bin/env bash
# Isolated MySQL/Redis/RocketMQ/ES stack + application launcher for integration tests and
# benchmarks. It never touches the dev stack (fixed ports 3306/6379/9876/10911/9200) and only
# removes resources of its own compose project.
#
#   scripts/stack.sh up                 start dependencies (named volumes, 127.0.0.1 ports)
#   scripts/stack.sh env                print the variables the app/tests need (eval-able)
#   scripts/stack.sh it 'Seckill*IT'    run tests against this stack
#   scripts/stack.sh build              package the app jar with Java 8
#   scripts/stack.sh app-start|app-stop start/stop the jar (pinned with taskset when APP_CPUS set)
#   scripts/stack.sh pin                pin dependency containers to DEPS_CPUS
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
APP_PORT="${APP_PORT:-28083}"
MANAGEMENT_PORT="${MANAGEMENT_PORT:-28184}"
STACK_SUBNET="${STACK_SUBNET:-172.30.56.0/24}"
MYSQL_PASSWORD="${STACK_MYSQL_PASSWORD:-ld-stack-mysql}"
REDIS_PASSWORD="${STACK_REDIS_PASSWORD:-ld-stack-redis}"
SCHEMA="${STACK_SCHEMA:-local_deals}"
RUN_DIR="${RUN_DIR:-${PROJECT_DIR}/benchmark/v2/run/${STACK_ID}}"
JAVA8_HOME="${JAVA8_HOME:-${HOME}/.jdks/dragonwell-ex-1.8.0_472}"
APP_CPUS="${APP_CPUS:-}"
DEPS_CPUS="${DEPS_CPUS:-}"
APP_JAVA_OPTS="${APP_JAVA_OPTS:--Xms2g -Xmx2g}"
# Broker store root on a disk with free space; read from the environment or the local .env.
# Each stack gets <root>/<stack-name>; unset -> a named docker volume.
ROCKETMQ_STORE_ROOT="${LOCAL_DEALS_ROCKETMQ_STORE_ROOT:-$(sed -n 's/^LOCAL_DEALS_ROCKETMQ_STORE_ROOT=//p' "${PROJECT_DIR}/.env" 2>/dev/null | tail -1)}"
BROKER_STORE=rocketmq-store
[[ -z "$ROCKETMQ_STORE_ROOT" ]] || BROKER_STORE="${ROCKETMQ_STORE_ROOT}/${STACK_NAME}"
FORBIDDEN_PORTS=(3306 6379 9876 10911 9200 8083 8088)

fail() { echo "stack: $*" >&2; exit 1; }

check_isolation() {
  [[ "$STACK_ID" =~ ^[a-z0-9][a-z0-9-]{1,30}$ ]] || fail "STACK_ID must match [a-z0-9-]{2,31}"
  local port forbidden
  for port in "$MYSQL_PORT" "$REDIS_PORT" "$NAMESRV_PORT" "$BROKER_PORT" "$ES_PORT" "$APP_PORT" "$MANAGEMENT_PORT"; do
    for forbidden in "${FORBIDDEN_PORTS[@]}"; do
      [[ "$port" != "$forbidden" ]] || fail "port ${port} belongs to the dev stack"
    done
  done
}

compose() {
  env STACK_NAME="$STACK_NAME" STACK_RESTART=no STACK_BIND=127.0.0.1 STACK_SUBNET="$STACK_SUBNET" \
    MYSQL_PORT="$MYSQL_PORT" REDIS_PORT="$REDIS_PORT" NAMESRV_PORT="$NAMESRV_PORT" \
    BROKER_PORT="$BROKER_PORT" ES_PORT="$ES_PORT" \
    MYSQL_ROOT_PASSWORD="$MYSQL_PASSWORD" MYSQL_DATABASE="$SCHEMA" \
    LOCAL_DEALS_REDIS_PASSWORD="$REDIS_PASSWORD" \
    MYSQL_VOLUME=mysql-data REDIS_VOLUME=redis-data ES_VOLUME=es-data BROKER_STORE="$BROKER_STORE" \
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
export SPRING_ELASTICSEARCH_REST_URIS=http://127.0.0.1:${ES_PORT}
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
  docker run --rm -v "${BROKER_STORE}:/store" --entrypoint chown apache/rocketmq:4.9.4 -R 3000:3000 /store 2>/dev/null ||
    docker run --rm -u 0 -v "${BROKER_STORE}:/store" --entrypoint chown apache/rocketmq:4.9.4 -R 3000:3000 /store
}

up() {
  check_isolation
  prepare_broker_store
  compose up -d mysql redis namesrv broker elasticsearch
  wait_for MySQL mysql_ready
  wait_for Redis redis_ready
  wait_for Elasticsearch es_ready
  wait_for RocketMQ rmq_ready
  local topic group
  for topic in seckill-order-topic mysql-sync-topic; do
    docker exec "${STACK_NAME}-broker" sh mqadmin updateTopic -n namesrv:9876 -c "$STACK_NAME" -t "$topic" >/dev/null
  done
  for group in seckill-consumer-group es-sync-consumer-group; do
    docker exec "${STACK_NAME}-broker" sh mqadmin updateSubGroup -n namesrv:9876 -c "$STACK_NAME" -g "$group" >/dev/null
  done
  [[ -z "$DEPS_CPUS" ]] || pin
  echo "stack ${STACK_NAME} ready: mysql=${MYSQL_PORT} redis=${REDIS_PORT} namesrv=${NAMESRV_PORT} broker=${BROKER_PORT} es=${ES_PORT}"
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
  if [[ "$BROKER_STORE" == /* && -d "$BROKER_STORE" ]]; then
    docker run --rm -u 0 -v "$(dirname "$BROKER_STORE"):/root-store" --entrypoint rm apache/rocketmq:4.9.4 \
      -rf "/root-store/$(basename "$BROKER_STORE")"
  fi
}

status() {
  compose ps
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
  # Java 8 is the project target; MyBatis-Plus 3.4 lambda queries fail on newer JDKs.
  (cd "$PROJECT_DIR" && JAVA_HOME="$JAVA8_HOME" mvn -o -q test -Dtest="$pattern" -DfailIfNoTests=false)
}

build() {
  [[ -x "${JAVA8_HOME}/bin/java" ]] || fail "JAVA8_HOME has no java: ${JAVA8_HOME}"
  (cd "$PROJECT_DIR" && JAVA_HOME="$JAVA8_HOME" mvn -q package -DskipTests)
}

app_jar() {
  local jar
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
  nohup "${pin_cmd[@]}" "${JAVA8_HOME}/bin/java" $APP_JAVA_OPTS ${APP_EXTRA_JAVA_OPTS:-} -jar "$jar" \
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

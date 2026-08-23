#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="${PROJECT_DIR}/docker-compose.pre-m8.yml"
ACTION="${1:-}"

M7RC_RUN_ID="${M7RC_RUN_ID:-m7rc_20260823a}"
M7RC_PROJECT="${M7RC_PROJECT:-m7rc-20260823a}"
M7RC_MYSQL_PORT="${M7RC_MYSQL_PORT:-28330}"
M7RC_REDIS_PORT="${M7RC_REDIS_PORT:-28379}"
M7RC_RMQ_NAMESRV_PORT="${M7RC_RMQ_NAMESRV_PORT:-29876}"
M7RC_RMQ_BROKER_PORT="${M7RC_RMQ_BROKER_PORT:-29111}"
M7RC_RMQ_BROKER_HA_PORT="${M7RC_RMQ_BROKER_HA_PORT:-29112}"
M7RC_ES_PORT="${M7RC_ES_PORT:-29200}"
M7RC_APP_PORT="${M7RC_APP_PORT:-28083}"
M7RC_MANAGEMENT_PORT="${M7RC_MANAGEMENT_PORT:-28184}"
M7RC_MYSQL_PASSWORD="${M7RC_MYSQL_PASSWORD:-M7rcOnly-20260823}"
M7RC_REDIS_PASSWORD="${M7RC_REDIS_PASSWORD:-M7rcRedis-20260823}"
M7RC_SCHEMA="${M7RC_SCHEMA:-m5b_${M7RC_RUN_ID//-/_}}"
M7RC_NETWORK_NAME="${M7RC_NETWORK_NAME:-${M7RC_PROJECT}_default}"
M7RC_ARTIFACT_DIR="${M7RC_ARTIFACT_DIR:-${PROJECT_DIR}/benchmark/pre-m8/${M7RC_RUN_ID}}"
M7RC_BROKER_CONF="${M7RC_BROKER_CONF:-${M7RC_ARTIFACT_DIR}/broker.conf}"
JAVA8_HOME="${JAVA8_HOME:-/home/sd101t/.jdks/dragonwell-ex-1.8.0_472}"
MAVEN_CMD="${MAVEN_CMD:-/home/sd101t/.m2/wrapper/dists/apache-maven-3.9.11/a2d47e15/bin/mvn}"
M7RC_APP_PID_FILE="${M7RC_APP_PID_FILE:-${M7RC_ARTIFACT_DIR}/app.pid}"
M7RC_APP_LOG="${M7RC_APP_LOG:-${M7RC_ARTIFACT_DIR}/app.log}"

LABEL_KEY="com.localdeals.m7rc.run-id"
ROLE_KEY="com.localdeals.m7rc.role"
FORBIDDEN_PORTS=(3306 6379 9876 10911 9200)
ALL_PORT_VARS=(M7RC_MYSQL_PORT M7RC_REDIS_PORT M7RC_RMQ_NAMESRV_PORT
  M7RC_RMQ_BROKER_PORT M7RC_RMQ_BROKER_HA_PORT M7RC_ES_PORT M7RC_APP_PORT M7RC_MANAGEMENT_PORT)

fail() {
  echo "M7-RC isolation gate failed: $*" >&2
  exit 1
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || fail "missing command: $1"
}

require_isolation() {
  [[ "$M7RC_RUN_ID" =~ ^m7rc_[a-z0-9][a-z0-9_-]{2,40}$ ]] ||
    fail "M7RC_RUN_ID must match m7rc_[a-z0-9][a-z0-9_-]{2,40}"
  [[ "$M7RC_PROJECT" =~ ^[a-z0-9][a-z0-9_-]{2,50}$ ]] ||
    fail "M7RC_PROJECT has unsafe format"
  [[ "$M7RC_SCHEMA" =~ ^m5b_[a-z0-9][a-z0-9_]{2,55}$ ]] ||
    fail "M7RC_SCHEMA has unsafe format"
  [[ "$M7RC_NETWORK_NAME" =~ ^[a-z0-9][a-z0-9_.-]{2,60}$ ]] ||
    fail "M7RC_NETWORK_NAME has unsafe format"
  for variable in "${ALL_PORT_VARS[@]}"; do
    local port="${!variable}"
    [[ "$port" =~ ^[0-9]+$ && "$port" -ge 1024 && "$port" -le 65534 ]] ||
      fail "${variable} must be an unprivileged TCP port"
    for forbidden in "${FORBIDDEN_PORTS[@]}"; do
      [[ "$port" != "$forbidden" ]] || fail "${variable} uses forbidden shared port ${port}"
    done
  done
  local distinct
  distinct="$(printf '%s\n' "${M7RC_MYSQL_PORT}" "${M7RC_REDIS_PORT}" \
    "${M7RC_RMQ_NAMESRV_PORT}" "${M7RC_RMQ_BROKER_PORT}" "${M7RC_RMQ_BROKER_HA_PORT}" \
    "${M7RC_ES_PORT}" "${M7RC_APP_PORT}" "${M7RC_MANAGEMENT_PORT}" | sort -u | wc -l)"
  [[ "$distinct" -eq "${#ALL_PORT_VARS[@]}" ]] || fail "all M7-RC ports must be distinct"
  [[ "$M7RC_MYSQL_PASSWORD" != "$M7RC_REDIS_PASSWORD" ]] || fail "MySQL and Redis passwords must differ"
}

compose() {
  env \
    M7RC_RUN_ID="$M7RC_RUN_ID" M7RC_PROJECT="$M7RC_PROJECT" \
    M7RC_MYSQL_PORT="$M7RC_MYSQL_PORT" M7RC_REDIS_PORT="$M7RC_REDIS_PORT" \
    M7RC_RMQ_NAMESRV_PORT="$M7RC_RMQ_NAMESRV_PORT" M7RC_RMQ_BROKER_PORT="$M7RC_RMQ_BROKER_PORT" \
    M7RC_RMQ_BROKER_HA_PORT="$M7RC_RMQ_BROKER_HA_PORT" M7RC_ES_PORT="$M7RC_ES_PORT" \
    M7RC_SCHEMA="$M7RC_SCHEMA" M7RC_NETWORK_NAME="$M7RC_NETWORK_NAME" \
    M7RC_BROKER_CONF="$M7RC_BROKER_CONF" \
    M7RC_MYSQL_PASSWORD="$M7RC_MYSQL_PASSWORD" M7RC_REDIS_PASSWORD="$M7RC_REDIS_PASSWORD" \
    docker compose --project-name "$M7RC_PROJECT" --file "$COMPOSE_FILE" "$@"
}

container_name() {
  printf '%s-%s\n' "$M7RC_RUN_ID" "$1"
}

assert_owned_container() {
  local name="$1"
  local run_id role
  run_id="$(docker inspect --format "{{index .Config.Labels \"${LABEL_KEY}\"}}" "$name" 2>/dev/null || true)"
  role="$(docker inspect --format "{{index .Config.Labels \"${ROLE_KEY}\"}}" "$name" 2>/dev/null || true)"
  [[ "$run_id" == "$M7RC_RUN_ID" ]] || fail "container ownership mismatch: ${name} run=${run_id}"
  [[ -n "$role" ]] || fail "container role label missing: ${name}"
}

assert_owned_network() {
  local network="$1"
  local project run_id
  project="$(docker network inspect --format '{{index .Labels "com.docker.compose.project"}}' "$network" 2>/dev/null || true)"
  run_id="$(docker network inspect --format "{{index .Labels \"${LABEL_KEY}\"}}" "$network" 2>/dev/null || true)"
  [[ "$project" == "$M7RC_PROJECT" ]] || fail "network project mismatch: ${network} project=${project}"
  [[ "$run_id" == "$M7RC_RUN_ID" || -z "$run_id" ]] || fail "network run-id mismatch: ${network}"
}

port_is_free() {
  local port="$1"
  python3 - "$port" <<'PY'
import socket
import sys

port = int(sys.argv[1])
sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
sock.settimeout(0.2)
try:
    sock.connect(("127.0.0.1", port))
except OSError:
    sys.exit(0)
finally:
    sock.close()
sys.exit(1)
PY
}

assert_ports_free() {
  for variable in "${ALL_PORT_VARS[@]}"; do
    local port="${!variable}"
    port_is_free "$port" || fail "127.0.0.1:${port} is already accepting connections (${variable})"
  done
}

write_broker_conf() {
  mkdir -p "$M7RC_ARTIFACT_DIR"
  [[ "$M7RC_BROKER_CONF" == "${M7RC_ARTIFACT_DIR}/"* ]] || fail "broker config must remain under run artifact directory"
  {
    printf 'brokerClusterName=%s\n' "$M7RC_PROJECT"
    printf 'brokerName=%s\n' "$M7RC_PROJECT"
    printf 'brokerId=0\n'
    printf 'brokerIP1=127.0.0.1\n'
    printf 'listenPort=%s\n' "$M7RC_RMQ_BROKER_PORT"
    printf 'haListenPort=%s\n' "$M7RC_RMQ_BROKER_HA_PORT"
    printf 'namesrvAddr=%s:9876\n' "$(container_name namesrv)"
    printf 'autoCreateTopicEnable=true\n'
    printf 'autoCreateSubscriptionGroup=true\n'
    printf 'deleteWhen=04\nfileReservedTime=24\n'
    printf 'brokerRole=ASYNC_MASTER\nflushDiskType=ASYNC_FLUSH\n'
  } > "$M7RC_BROKER_CONF"
}

mysql_exec() {
  docker exec -i "$(container_name mysql)" mysql -uroot "-p${M7RC_MYSQL_PASSWORD}" "$@"
}

wait_for() {
  local description="$1"
  shift
  local attempt
  for attempt in $(seq 1 90); do
    if "$@" >/dev/null 2>&1; then return 0; fi
    sleep 2
  done
  fail "timed out waiting for ${description}"
}

mysql_ready() {
  mysql_exec -e 'SELECT 1'
}

redis_ready() {
  redis-cli -h 127.0.0.1 -p "$M7RC_REDIS_PORT" -a "$M7RC_REDIS_PASSWORD" --no-auth-warning PING |
    grep -qx PONG
}

es_ready() {
  curl --fail --silent "http://127.0.0.1:${M7RC_ES_PORT}/_cluster/health" >/dev/null
}

rmq_ready() {
  docker exec "$(container_name broker)" sh mqadmin clusterList \
    -n "$(container_name namesrv):9876" | grep -q "$M7RC_PROJECT"
}

provision_sentinels() {
  local m6a="m6a_${M7RC_RUN_ID}" m6b="m6b_${M7RC_RUN_ID}" m6c="m6c_${M7RC_RUN_ID}"
  mysql_exec -e "
    CREATE DATABASE IF NOT EXISTS \`${m6a}_sentinel\`;
    CREATE TABLE IF NOT EXISTS \`${m6a}_sentinel\`.m6a_run_sentinel
      (run_id varchar(64) NOT NULL PRIMARY KEY, purpose varchar(64) NOT NULL);
    DELETE FROM \`${m6a}_sentinel\`.m6a_run_sentinel;
    INSERT INTO \`${m6a}_sentinel\`.m6a_run_sentinel VALUES ('${m6a}','m6a-isolated-mysql');
    CREATE DATABASE IF NOT EXISTS \`${m6b}_sentinel\`;
    CREATE TABLE IF NOT EXISTS \`${m6b}_sentinel\`.m6b_run_sentinel
      (run_id varchar(64) NOT NULL PRIMARY KEY, purpose varchar(64) NOT NULL);
    DELETE FROM \`${m6b}_sentinel\`.m6b_run_sentinel;
    INSERT INTO \`${m6b}_sentinel\`.m6b_run_sentinel VALUES ('${m6b}','m6b-isolated-mysql');
    CREATE DATABASE IF NOT EXISTS \`${m6c}_sentinel\`;
    CREATE TABLE IF NOT EXISTS \`${m6c}_sentinel\`.m6c_run_sentinel
      (run_id varchar(64) NOT NULL PRIMARY KEY, purpose varchar(64) NOT NULL);
    DELETE FROM \`${m6c}_sentinel\`.m6c_run_sentinel;
    INSERT INTO \`${m6c}_sentinel\`.m6c_run_sentinel VALUES ('${m6c}','m6c-isolated-mysql');
  " >/dev/null
  redis-cli -h 127.0.0.1 -p "$M7RC_REDIS_PORT" -a "$M7RC_REDIS_PASSWORD" --no-auth-warning \
    SET "m5b:sentinel:${M7RC_RUN_ID}" "$M7RC_RUN_ID" >/dev/null
  redis-cli -h 127.0.0.1 -p "$M7RC_REDIS_PORT" -a "$M7RC_REDIS_PASSWORD" --no-auth-warning \
    SET "m6c:sentinel:${m6c}" "$m6c" >/dev/null
}

provision_rmq_scope() {
  local namesrv="$(container_name namesrv):9876" broker="$(container_name broker)"
  for topic in seckill-order-topic mysql-sync-topic; do
    docker exec "$broker" sh mqadmin updateTopic -n "$namesrv" -c "$M7RC_PROJECT" -t "$topic" >/dev/null
  done
  for group in seckill-consumer-group es-sync-consumer-group local-deals-producer-group; do
    docker exec "$broker" sh mqadmin updateSubGroup -n "$namesrv" -c "$M7RC_PROJECT" -g "$group" >/dev/null
  done
}

up() {
  require_cmd docker
  require_cmd docker
  require_cmd curl
  require_cmd mysql
  require_cmd redis-cli
  require_cmd python3
  require_isolation
  assert_ports_free
  [[ ! -e "$M7RC_BROKER_CONF" ]] || fail "run artifact already exists; choose a new run-id or inspect it: $M7RC_BROKER_CONF"
  if docker ps -a --filter "label=${LABEL_KEY}=${M7RC_RUN_ID}" --format '{{.Names}}' | grep -q .; then
    fail "owned containers already exist for ${M7RC_RUN_ID}"
  fi
  if docker network inspect "$M7RC_NETWORK_NAME" >/dev/null 2>&1; then
    fail "network already exists: ${M7RC_NETWORK_NAME}"
  fi
  write_broker_conf
  compose up -d
  wait_for MySQL mysql_ready
  wait_for Redis redis_ready
  wait_for Elasticsearch es_ready
  wait_for RocketMQ rmq_ready
  provision_sentinels
  provision_rmq_scope
  compose ps
  echo "M7-RC dependencies ready: run_id=${M7RC_RUN_ID} project=${M7RC_PROJECT} schema=${M7RC_SCHEMA}"
  echo "mysql=127.0.0.1:${M7RC_MYSQL_PORT} redis=127.0.0.1:${M7RC_REDIS_PORT} namesrv=127.0.0.1:${M7RC_RMQ_NAMESRV_PORT} broker=127.0.0.1:${M7RC_RMQ_BROKER_PORT} es=127.0.0.1:${M7RC_ES_PORT}"
}

status() {
  require_cmd docker
  require_cmd curl
  require_cmd redis-cli
  require_isolation
  for role in mysql redis namesrv broker elasticsearch; do
    assert_owned_container "$(container_name "$role")"
  done
  assert_owned_network "$M7RC_NETWORK_NAME"
  compose ps
  mysql_ready
  redis_ready
  es_ready
  rmq_ready
  if [[ -f "$M7RC_APP_PID_FILE" ]]; then
    local pid
    pid="$(<"$M7RC_APP_PID_FILE")"
    kill -0 "$pid" 2>/dev/null || fail "application PID is not running: ${pid}"
    curl --fail --silent "http://127.0.0.1:${M7RC_MANAGEMENT_PORT}/actuator/health/readiness" >/dev/null
    echo "application=running pid=${pid} port=${M7RC_APP_PORT} management=${M7RC_MANAGEMENT_PORT}"
  else
    echo "application=not-started; dependencies=ready"
  fi
  echo "M7-RC status passed for ${M7RC_RUN_ID}."
}

print_env() {
  require_isolation
  cat <<EOF
M7RC_RUN_ID=${M7RC_RUN_ID}
M7RC_PROJECT=${M7RC_PROJECT}
M7RC_MYSQL_PORT=${M7RC_MYSQL_PORT}
M7RC_REDIS_PORT=${M7RC_REDIS_PORT}
M7RC_RMQ_NAMESRV_PORT=${M7RC_RMQ_NAMESRV_PORT}
M7RC_RMQ_BROKER_PORT=${M7RC_RMQ_BROKER_PORT}
M7RC_ES_PORT=${M7RC_ES_PORT}
M7RC_APP_PORT=${M7RC_APP_PORT}
M7RC_MANAGEMENT_PORT=${M7RC_MANAGEMENT_PORT}
M7RC_SCHEMA=${M7RC_SCHEMA}
M7RC_MYSQL_PASSWORD=${M7RC_MYSQL_PASSWORD}
M7RC_REDIS_PASSWORD=${M7RC_REDIS_PASSWORD}
M7RC_MYSQL_URL=jdbc:mysql://127.0.0.1:${M7RC_MYSQL_PORT}/${M7RC_SCHEMA}?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai
M7RC_ARTIFACT_DIR=${M7RC_ARTIFACT_DIR}
EOF
}

app_jar() {
  local candidate
  for candidate in "$PROJECT_DIR"/target/*.jar; do
    [[ -f "$candidate" ]] || continue
    [[ "$candidate" != *-sources.jar && "$candidate" != *-javadoc.jar ]] || continue
    printf '%s\n' "$candidate"
    return 0
  done
  fail "no application jar under target; run Java 8 package first"
}

application_command() {
  local jar="$1"
  local datasource="jdbc:mysql://127.0.0.1:${M7RC_MYSQL_PORT}/${M7RC_SCHEMA}?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai"
  env JAVA_HOME="$JAVA8_HOME" PATH="$JAVA8_HOME/bin:$PATH" \
    "$JAVA8_HOME/bin/java" -jar "$jar" \
    --server.port="$M7RC_APP_PORT" \
    --management.server.port="$M7RC_MANAGEMENT_PORT" \
    --spring.datasource.url="$datasource" \
    --spring.datasource.username=root \
    --spring.datasource.password="$M7RC_MYSQL_PASSWORD" \
    --spring.redis.host=127.0.0.1 \
    --spring.redis.port="$M7RC_REDIS_PORT" \
    --spring.redis.password="$M7RC_REDIS_PASSWORD" \
    --spring.elasticsearch.rest.uris="http://127.0.0.1:${M7RC_ES_PORT}" \
    --rocketmq.name-server="127.0.0.1:${M7RC_RMQ_NAMESRV_PORT}" \
    --local-deals.seckill.topic=seckill-order-topic \
    --local-deals.seckill.consumer-group=seckill-consumer-group \
    --local-deals.es-sync.topic=mysql-sync-topic \
    --local-deals.es-sync.consumer-group=es-sync-consumer-group \
    --local-deals.traffic.seckill.activity-limit=2000 \
    --local-deals.traffic.seckill.ip-limit=2000 \
    --local-deals.voucher-batch.job-worker-enabled=false \
    --local-deals.voucher-batch.notification-worker-enabled=false
}

app_start() {
  require_cmd java
  require_cmd curl
  require_isolation
  [[ -x "$JAVA8_HOME/bin/java" ]] || fail "JAVA8_HOME has no executable java: ${JAVA8_HOME}"
  [[ -f "$M7RC_BROKER_CONF" ]] || fail "dependencies are not provisioned for this run-id"
  [[ ! -f "$M7RC_APP_PID_FILE" ]] || fail "application pid file exists; stop it or choose a new run-id"
  port_is_free "$M7RC_APP_PORT" || fail "application port is already occupied"
  port_is_free "$M7RC_MANAGEMENT_PORT" || fail "management port is already occupied"
  local jar
  jar="$(app_jar)"
  mkdir -p "$M7RC_ARTIFACT_DIR"
  application_command "$jar" >"$M7RC_APP_LOG" 2>&1 &
  echo "$!" > "$M7RC_APP_PID_FILE"
  echo "application started pid=$(<"$M7RC_APP_PID_FILE") log=${M7RC_APP_LOG}"
}

app_run() {
  require_cmd curl
  require_isolation
  [[ -x "$JAVA8_HOME/bin/java" ]] || fail "JAVA8_HOME has no executable java: ${JAVA8_HOME}"
  [[ -f "$M7RC_BROKER_CONF" ]] || fail "dependencies are not provisioned for this run-id"
  port_is_free "$M7RC_APP_PORT" || fail "application port is already occupied"
  port_is_free "$M7RC_MANAGEMENT_PORT" || fail "management port is already occupied"
  local jar
  jar="$(app_jar)"
  mkdir -p "$M7RC_ARTIFACT_DIR"
  echo "$$" > "$M7RC_APP_PID_FILE"
  trap 'rm -f "$M7RC_APP_PID_FILE"' EXIT
  application_command "$jar"
}

app_stop() {
  [[ -f "$M7RC_APP_PID_FILE" ]] || { echo "application not started"; return 0; }
  local pid
  pid="$(<"$M7RC_APP_PID_FILE")"
  [[ "$pid" =~ ^[0-9]+$ ]] || fail "invalid application pid file"
  if kill -0 "$pid" 2>/dev/null; then
    kill "$pid"
    for _ in $(seq 1 30); do
      kill -0 "$pid" 2>/dev/null || break
      sleep 1
    done
    kill -0 "$pid" 2>/dev/null && kill -9 "$pid"
  fi
  rm -f "$M7RC_APP_PID_FILE"
  echo "application stopped for ${M7RC_RUN_ID}"
}

down() {
  require_cmd docker
  require_isolation
  app_stop
  for role in mysql redis namesrv broker elasticsearch; do
    local name="$(container_name "$role")"
    if docker inspect "$name" >/dev/null 2>&1; then assert_owned_container "$name"; fi
  done
  if docker network inspect "$M7RC_NETWORK_NAME" >/dev/null 2>&1; then
    assert_owned_network "$M7RC_NETWORK_NAME"
  fi
  compose down --volumes --remove-orphans
  if docker ps -a --filter "label=${LABEL_KEY}=${M7RC_RUN_ID}" --format '{{.Names}}' | grep -q .; then
    fail "owned M7-RC containers remain after exact compose cleanup"
  fi
  if docker network inspect "$M7RC_NETWORK_NAME" >/dev/null 2>&1; then
    fail "owned M7-RC network remains after exact compose cleanup"
  fi
  echo "Removed only Compose project ${M7RC_PROJECT} resources for run ${M7RC_RUN_ID}; evidence under ${M7RC_ARTIFACT_DIR} was retained."
}

require_isolation
case "$ACTION" in
  up) up ;;
  status) status ;;
  env) print_env ;;
  app-start) app_start ;;
  app-run) app_run ;;
  app-stop) app_stop ;;
  down) down ;;
  *)
    echo "Usage: $0 {up|status|env|app-start|app-run|app-stop|down}" >&2
    exit 2
    ;;
esac

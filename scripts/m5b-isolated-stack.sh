#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ACTION="${1:-}"

fail() {
  echo "M5B isolation gate failed: $*" >&2
  exit 1
}

require_env() {
  local name="$1"
  [[ -n "${!name:-}" ]] || fail "${name} must be set explicitly"
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || fail "missing command: $1"
}

require_port() {
  local name="$1"
  local value="${!name}"
  [[ "$value" =~ ^[0-9]+$ && "$value" -ge 1024 && "$value" -le 65535 ]] ||
    fail "${name} must be an unprivileged TCP port"
}

require_isolation() {
  [[ "${M5B_ISOLATED:-}" == "true" ]] || fail "set M5B_ISOLATED=true"
  require_env M5B_RUN_ID
  [[ "$M5B_RUN_ID" =~ ^[a-z0-9][a-z0-9-]{5,30}$ ]] ||
    fail "M5B_RUN_ID must match [a-z0-9][a-z0-9-]{5,30}"
  require_env M5B_APP_HOST
  [[ "$M5B_APP_HOST" == "127.0.0.1" ]] || fail "M5B_APP_HOST must be 127.0.0.1"
  local name
  for name in M5B_APP_PORT M5B_MANAGEMENT_PORT M5B_MYSQL_PORT M5B_REDIS_PORT \
      M5B_ES_PORT M5B_RMQ_NAMESRV_PORT M5B_RMQ_BROKER_PORT; do
    require_env "$name"
    require_port "$name"
  done
  require_env M5B_MYSQL_PASSWORD
  require_env M5B_REDIS_PASSWORD

  local ports=("$M5B_APP_PORT" "$M5B_MANAGEMENT_PORT" "$M5B_MYSQL_PORT"
    "$M5B_REDIS_PORT" "$M5B_ES_PORT" "$M5B_RMQ_NAMESRV_PORT"
    "$M5B_RMQ_BROKER_PORT" "$((M5B_RMQ_BROKER_PORT + 1))")
  local unique
  unique="$(printf '%s\n' "${ports[@]}" | sort -u | wc -l)"
  [[ "$unique" -eq "${#ports[@]}" ]] || fail "all M5B ports, including Broker HA, must be unique"
}

container_name() {
  printf 'm5b-%s-%s\n' "$M5B_RUN_ID" "$1"
}

schema_name() {
  printf 'm5b_%s\n' "${M5B_RUN_ID//-/_}"
}

assert_owned_container() {
  local name="$1"
  local label
  label="$(docker inspect --format '{{ index .Config.Labels "com.localdeals.m5b.run-id" }}' \
    "$name" 2>/dev/null || true)"
  [[ "$label" == "$M5B_RUN_ID" ]] || fail "container is not owned by this run: $name"
}

wait_until() {
  local description="$1"
  shift
  local attempt
  for attempt in $(seq 1 90); do
    if "$@" >/dev/null 2>&1; then
      return 0
    fi
    sleep 2
  done
  fail "timed out waiting for ${description}"
}

mysql_ready() {
  docker exec "$(container_name mysql)" mysqladmin ping \
    -uroot "-p${M5B_MYSQL_PASSWORD}" --silent
}

redis_ready() {
  docker exec "$(container_name redis)" redis-cli \
    -a "$M5B_REDIS_PASSWORD" --no-auth-warning PING | grep -qx PONG
}

es_ready() {
  curl --fail --silent "http://127.0.0.1:${M5B_ES_PORT}/_cluster/health" >/dev/null
}

rmq_ready() {
  docker exec "$(container_name broker)" sh mqadmin clusterList \
    -n "$(container_name namesrv):9876" | grep -q "m5b-${M5B_RUN_ID}"
}

write_redis_sentinel() {
  docker exec "$(container_name redis)" redis-cli \
    -a "$M5B_REDIS_PASSWORD" --no-auth-warning \
    SET "m5b:sentinel:${M5B_RUN_ID}" "$M5B_RUN_ID" >/dev/null
}

verify_sentinels() {
  local mysql_value redis_value
  mysql_value="$(docker exec "$(container_name mysql)" mysql -N -s \
    -uroot "-p${M5B_MYSQL_PASSWORD}" "$(schema_name)" -e "SELECT DATABASE()")"
  [[ "$mysql_value" == "$(schema_name)" ]] || fail "MySQL schema identity mismatch"
  redis_value="$(docker exec "$(container_name redis)" redis-cli \
    -a "$M5B_REDIS_PASSWORD" --no-auth-warning --raw \
    GET "m5b:sentinel:${M5B_RUN_ID}")"
  [[ "$redis_value" == "$M5B_RUN_ID" ]] || fail "Redis sentinel mismatch"
}

up() {
  local network="m5b-${M5B_RUN_ID}-net"
  local artifact_dir="${PROJECT_DIR}/benchmark/m5b/${M5B_RUN_ID}"
  local mysql_name redis_name es_name namesrv_name broker_name schema
  mysql_name="$(container_name mysql)"
  redis_name="$(container_name redis)"
  es_name="$(container_name es)"
  namesrv_name="$(container_name namesrv)"
  broker_name="$(container_name broker)"
  schema="$(schema_name)"
  [[ "$schema" != "local_deals" && "$schema" != "hmdp" ]] || fail "unsafe schema name"

  local name
  for name in "$mysql_name" "$redis_name" "$es_name" "$namesrv_name" "$broker_name"; do
    if docker inspect "$name" >/dev/null 2>&1; then
      fail "container already exists; inspect ownership before cleanup: $name"
    fi
  done
  if docker network inspect "$network" >/dev/null 2>&1; then
    fail "network already exists: $network"
  fi

  mkdir -p "$artifact_dir"
  docker network create --label "com.localdeals.m5b.run-id=${M5B_RUN_ID}" "$network" >/dev/null

  docker run -d --name "$mysql_name" --network "$network" \
    --label "com.localdeals.m5b.run-id=${M5B_RUN_ID}" \
    -p "127.0.0.1:${M5B_MYSQL_PORT}:3306" \
    -e MYSQL_ROOT_PASSWORD="$M5B_MYSQL_PASSWORD" \
    -e MYSQL_DATABASE="$schema" \
    mysql:8.0 --default-authentication-plugin=mysql_native_password >/dev/null

  docker run -d --name "$redis_name" --network "$network" \
    --label "com.localdeals.m5b.run-id=${M5B_RUN_ID}" \
    -p "127.0.0.1:${M5B_REDIS_PORT}:6379" \
    redis:6.2 redis-server --save '' --appendonly no \
    --requirepass "$M5B_REDIS_PASSWORD" >/dev/null

  docker run -d --name "$es_name" --network "$network" \
    --label "com.localdeals.m5b.run-id=${M5B_RUN_ID}" \
    -p "127.0.0.1:${M5B_ES_PORT}:9200" \
    -e discovery.type=single-node \
    -e "cluster.name=m5b-${M5B_RUN_ID}" \
    -e "ES_JAVA_OPTS=-Xms384m -Xmx384m" \
    "${M5B_ES_IMAGE:-hm-dianping-elasticsearch:latest}" >/dev/null

  docker run -d --name "$namesrv_name" --network "$network" \
    --label "com.localdeals.m5b.run-id=${M5B_RUN_ID}" \
    -p "127.0.0.1:${M5B_RMQ_NAMESRV_PORT}:9876" \
    apache/rocketmq:4.9.4 sh mqnamesrv >/dev/null

  local broker_conf="${artifact_dir}/broker.conf"
  {
    printf 'brokerClusterName=m5b-%s\n' "$M5B_RUN_ID"
    printf 'brokerName=m5b-%s\n' "$M5B_RUN_ID"
    printf 'brokerId=0\n'
    printf 'brokerIP1=127.0.0.1\n'
    printf 'listenPort=%s\n' "$M5B_RMQ_BROKER_PORT"
    printf 'haListenPort=%s\n' "$((M5B_RMQ_BROKER_PORT + 1))"
    printf 'namesrvAddr=%s:9876\n' "$namesrv_name"
    printf 'autoCreateTopicEnable=true\nautoCreateSubscriptionGroup=true\n'
    printf 'deleteWhen=04\nfileReservedTime=24\nbrokerRole=ASYNC_MASTER\nflushDiskType=ASYNC_FLUSH\n'
  } > "$broker_conf"
  docker run -d --name "$broker_name" --network "$network" \
    --label "com.localdeals.m5b.run-id=${M5B_RUN_ID}" \
    -p "127.0.0.1:${M5B_RMQ_BROKER_PORT}:${M5B_RMQ_BROKER_PORT}" \
    -p "127.0.0.1:$((M5B_RMQ_BROKER_PORT + 1)):$((M5B_RMQ_BROKER_PORT + 1))" \
    -v "${broker_conf}:/home/rocketmq/rocketmq-4.9.4/conf/m5b-broker.conf:ro" \
    apache/rocketmq:4.9.4 sh mqbroker \
    -c /home/rocketmq/rocketmq-4.9.4/conf/m5b-broker.conf >/dev/null

  wait_until MySQL mysql_ready
  wait_until Redis redis_ready
  wait_until Elasticsearch es_ready
  wait_until RocketMQ rmq_ready
  write_redis_sentinel
  verify_sentinels

  echo "M5B isolated stack is ready."
  echo "run_id=${M5B_RUN_ID} schema=${schema}"
  echo "mysql=127.0.0.1:${M5B_MYSQL_PORT} redis=127.0.0.1:${M5B_REDIS_PORT}"
  echo "elasticsearch=127.0.0.1:${M5B_ES_PORT} nameserver=127.0.0.1:${M5B_RMQ_NAMESRV_PORT}"
}

status() {
  local name resolved
  for name in mysql redis es namesrv broker; do
    resolved="$(container_name "$name")"
    assert_owned_container "$resolved"
    docker inspect --format '{{.Name}} {{.State.Status}}' "$resolved"
  done
  mysql_ready
  redis_ready
  es_ready
  rmq_ready
  verify_sentinels
  echo "M5B isolation checks passed."
}

redis_action() {
  local action="$1"
  local redis_name
  redis_name="$(container_name redis)"
  assert_owned_container "$redis_name"
  case "$action" in
    pause) docker pause "$redis_name" >/dev/null ;;
    unpause) docker unpause "$redis_name" >/dev/null ;;
    stop) docker stop --time 10 "$redis_name" >/dev/null ;;
    start)
      docker start "$redis_name" >/dev/null
      wait_until Redis redis_ready
      write_redis_sentinel
      ;;
    *) fail "unsupported Redis action: $action" ;;
  esac
  echo "Redis ${action} completed for owned container ${redis_name}."
}

mysql_action() {
  local action="$1"
  local mysql_name
  mysql_name="$(container_name mysql)"
  assert_owned_container "$mysql_name"
  case "$action" in
    stop) docker stop --time 10 "$mysql_name" >/dev/null ;;
    start)
      docker start "$mysql_name" >/dev/null
      wait_until MySQL mysql_ready
      ;;
    *) fail "unsupported MySQL action: $action" ;;
  esac
  echo "MySQL ${action} completed for owned container ${mysql_name}."
}

down() {
  local name resolved
  for name in mysql redis es namesrv broker; do
    resolved="$(container_name "$name")"
    if docker inspect "$resolved" >/dev/null 2>&1; then
      assert_owned_container "$resolved"
    fi
  done
  for name in broker namesrv es redis mysql; do
    resolved="$(container_name "$name")"
    if docker inspect "$resolved" >/dev/null 2>&1; then
      docker rm -f "$resolved" >/dev/null ||
        echo "Residual container requires manual cleanup: $resolved" >&2
    fi
  done
  local network="m5b-${M5B_RUN_ID}-net"
  if docker network inspect "$network" >/dev/null 2>&1; then
    local label
    label="$(docker network inspect --format '{{ index .Labels "com.localdeals.m5b.run-id" }}' "$network")"
    [[ "$label" == "$M5B_RUN_ID" ]] || fail "network ownership mismatch: $network"
    docker network rm "$network" >/dev/null ||
      echo "Residual network requires manual cleanup: $network" >&2
  fi
  echo "Removed only resources labelled for M5B run ${M5B_RUN_ID}; container data is not recoverable."
}

require_cmd docker
require_cmd curl
require_isolation

case "$ACTION" in
  up) up ;;
  status) status ;;
  redis-pause) redis_action pause ;;
  redis-unpause) redis_action unpause ;;
  redis-stop) redis_action stop ;;
  redis-start) redis_action start ;;
  mysql-stop) mysql_action stop ;;
  mysql-start) mysql_action start ;;
  down) down ;;
  *)
    echo "Usage: M5B_ISOLATED=true M5B_RUN_ID=... M5B_*_PORT=... $0 up|status|redis-pause|redis-unpause|redis-stop|redis-start|mysql-stop|mysql-start|down" >&2
    exit 2
    ;;
esac

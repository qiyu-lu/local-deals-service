#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ACTION="${1:-}"

fail() {
  echo "M5A isolation gate failed: $*" >&2
  exit 1
}

require_env() {
  local name="$1"
  [[ -n "${!name:-}" ]] || fail "${name} must be set explicitly"
}

require_isolation() {
  [[ "${M5A_ISOLATED:-}" == "true" ]] || fail "set M5A_ISOLATED=true"
  require_env M5A_RUN_ID
  [[ "$M5A_RUN_ID" =~ ^[a-z0-9][a-z0-9-]{5,30}$ ]] ||
    fail "M5A_RUN_ID must match [a-z0-9][a-z0-9-]{5,30}"
  require_env M5A_MYSQL_PORT
  require_env M5A_MYSQL_PASSWORD
  require_env M5A_REDIS_PORT
  require_env M5A_REDIS_PASSWORD
  require_env M5A_ES_PORT
  require_env M5A_RMQ_NAMESRV_PORT
  require_env M5A_RMQ_BROKER_PORT
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || fail "missing command: $1"
}

container_name() {
  printf 'm5a-%s-%s\n' "$M5A_RUN_ID" "$1"
}

assert_owned_container() {
  local name="$1"
  local label
  label="$(docker inspect --format '{{ index .Config.Labels "com.localdeals.m5a.run-id" }}' "$name" 2>/dev/null || true)"
  [[ "$label" == "$M5A_RUN_ID" ]] || fail "container is not owned by this run: $name"
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
    -uroot "-p${M5A_MYSQL_PASSWORD}" --silent
}

redis_ready() {
  docker exec "$(container_name redis)" redis-cli \
    -a "$M5A_REDIS_PASSWORD" --no-auth-warning PING | grep -qx PONG
}

es_ready() {
  curl --fail --silent "http://127.0.0.1:${M5A_ES_PORT}/_cluster/health" >/dev/null
}

rmq_ready() {
  docker exec "$(container_name broker)" sh mqadmin clusterList \
    -n "$(container_name namesrv):9876" | grep -q "m5a-${M5A_RUN_ID}"
}

up() {
  local network="m5a-${M5A_RUN_ID}-net"
  local artifact_dir="${PROJECT_DIR}/benchmark/m5a/${M5A_RUN_ID}"
  local mysql_name redis_name es_name namesrv_name broker_name
  mysql_name="$(container_name mysql)"
  redis_name="$(container_name redis)"
  es_name="$(container_name es)"
  namesrv_name="$(container_name namesrv)"
  broker_name="$(container_name broker)"
  local schema="m5a_${M5A_RUN_ID//-/_}"

  [[ "$schema" != "local_deals" && "$schema" != "hmdp" ]] || fail "unsafe schema name"
  for name in "$mysql_name" "$redis_name" "$es_name" "$namesrv_name" "$broker_name"; do
    if docker inspect "$name" >/dev/null 2>&1; then
      fail "container already exists; run status/down after verifying ownership: $name"
    fi
  done
  if docker network inspect "$network" >/dev/null 2>&1; then
    fail "network already exists: $network"
  fi

  mkdir -p "$artifact_dir"
  docker network create --label "com.localdeals.m5a.run-id=${M5A_RUN_ID}" "$network" >/dev/null

  docker run -d --name "$mysql_name" --network "$network" \
    --label "com.localdeals.m5a.run-id=${M5A_RUN_ID}" \
    -p "127.0.0.1:${M5A_MYSQL_PORT}:3306" \
    -e MYSQL_ROOT_PASSWORD="$M5A_MYSQL_PASSWORD" \
    -e MYSQL_DATABASE="$schema" \
    mysql:8.0 --default-authentication-plugin=mysql_native_password >/dev/null

  docker run -d --name "$redis_name" --network "$network" \
    --label "com.localdeals.m5a.run-id=${M5A_RUN_ID}" \
    -p "127.0.0.1:${M5A_REDIS_PORT}:6379" \
    redis:6.2 redis-server --save '' --appendonly no \
    --requirepass "$M5A_REDIS_PASSWORD" >/dev/null

  docker run -d --name "$es_name" --network "$network" \
    --label "com.localdeals.m5a.run-id=${M5A_RUN_ID}" \
    -p "127.0.0.1:${M5A_ES_PORT}:9200" \
    -e discovery.type=single-node \
    -e "cluster.name=m5a-${M5A_RUN_ID}" \
    -e "ES_JAVA_OPTS=-Xms384m -Xmx384m" \
    hm-dianping-elasticsearch:latest >/dev/null

  docker run -d --name "$namesrv_name" --network "$network" \
    --label "com.localdeals.m5a.run-id=${M5A_RUN_ID}" \
    -p "127.0.0.1:${M5A_RMQ_NAMESRV_PORT}:9876" \
    apache/rocketmq:4.9.4 sh mqnamesrv >/dev/null

  local broker_conf="${artifact_dir}/broker.conf"
  {
    printf 'brokerClusterName=m5a-%s\n' "$M5A_RUN_ID"
    printf 'brokerName=m5a-%s\n' "$M5A_RUN_ID"
    printf 'brokerId=0\n'
    printf 'brokerIP1=127.0.0.1\n'
    printf 'listenPort=%s\n' "$M5A_RMQ_BROKER_PORT"
    printf 'haListenPort=%s\n' "$((M5A_RMQ_BROKER_PORT + 1))"
    printf 'namesrvAddr=%s:9876\n' "$namesrv_name"
    printf 'autoCreateTopicEnable=true\n'
    printf 'autoCreateSubscriptionGroup=true\n'
    printf 'deleteWhen=04\nfileReservedTime=24\nbrokerRole=ASYNC_MASTER\nflushDiskType=ASYNC_FLUSH\n'
  } > "$broker_conf"
  docker run -d --name "$broker_name" --network "$network" \
    --label "com.localdeals.m5a.run-id=${M5A_RUN_ID}" \
    -p "127.0.0.1:${M5A_RMQ_BROKER_PORT}:${M5A_RMQ_BROKER_PORT}" \
    -p "127.0.0.1:$((M5A_RMQ_BROKER_PORT + 1)):$((M5A_RMQ_BROKER_PORT + 1))" \
    -v "${broker_conf}:/home/rocketmq/rocketmq-4.9.4/conf/m5a-broker.conf:ro" \
    apache/rocketmq:4.9.4 sh mqbroker -c /home/rocketmq/rocketmq-4.9.4/conf/m5a-broker.conf >/dev/null

  wait_until MySQL mysql_ready
  wait_until Redis redis_ready
  wait_until Elasticsearch es_ready
  wait_until RocketMQ rmq_ready

  docker exec "$redis_name" redis-cli -a "$M5A_REDIS_PASSWORD" --no-auth-warning \
    SET "m5a:sentinel:${M5A_RUN_ID}" "$M5A_RUN_ID" >/dev/null

  echo "M5A isolated stack is ready."
  echo "run_id=${M5A_RUN_ID} schema=${schema}"
  echo "mysql=127.0.0.1:${M5A_MYSQL_PORT} redis=127.0.0.1:${M5A_REDIS_PORT}"
  echo "elasticsearch=127.0.0.1:${M5A_ES_PORT} nameserver=127.0.0.1:${M5A_RMQ_NAMESRV_PORT}"
}

status() {
  local name
  for name in mysql redis es namesrv broker; do
    local resolved
    resolved="$(container_name "$name")"
    assert_owned_container "$resolved"
    docker inspect --format '{{.Name}} {{.State.Status}}' "$resolved"
  done
  mysql_ready
  redis_ready
  es_ready
  rmq_ready
  local sentinel
  sentinel="$(docker exec "$(container_name redis)" redis-cli \
    -a "$M5A_REDIS_PASSWORD" --no-auth-warning --raw GET "m5a:sentinel:${M5A_RUN_ID}")"
  [[ "$sentinel" == "$M5A_RUN_ID" ]] || fail "Redis sentinel mismatch"
  echo "M5A isolation checks passed."
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
  local network="m5a-${M5A_RUN_ID}-net"
  if docker network inspect "$network" >/dev/null 2>&1; then
    local label
    label="$(docker network inspect --format '{{ index .Labels "com.localdeals.m5a.run-id" }}' "$network")"
    [[ "$label" == "$M5A_RUN_ID" ]] || fail "network ownership mismatch: $network"
    docker network rm "$network" >/dev/null ||
      echo "Residual network requires manual cleanup: $network" >&2
  fi
  echo "Removed only resources labelled for M5A run ${M5A_RUN_ID}; container data is not recoverable."
}

require_cmd docker
require_cmd curl
require_isolation

case "$ACTION" in
  up) up ;;
  status) status ;;
  down) down ;;
  *) echo "Usage: M5A_ISOLATED=true M5A_RUN_ID=... M5A_*_PORT=... $0 up|status|down" >&2; exit 2 ;;
esac

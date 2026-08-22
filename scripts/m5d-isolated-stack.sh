#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ACTION="${1:-}"
LABEL_KEY="com.localdeals.m5d.run-id"

fail() {
  echo "M5D isolation gate failed: $*" >&2
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
  [[ "$value" =~ ^[0-9]+$ && "$value" -ge 1024 && "$value" -le 65534 ]] ||
    fail "${name} must be an unprivileged TCP port below 65535"
}

container_name() {
  printf 'm5d-%s-%s\n' "$M5D_RUN_ID" "$1"
}

schema_name() {
  printf 'm5d_%s\n' "${M5D_RUN_ID//-/_}"
}

network_name() {
  printf 'm5d-%s-net\n' "$M5D_RUN_ID"
}

cluster_name() {
  printf 'm5d-%s\n' "$M5D_RUN_ID"
}

require_isolation() {
  [[ "${M5D_ISOLATED:-}" == "true" ]] || fail "set M5D_ISOLATED=true"
  require_env M5D_RUN_ID
  [[ "$M5D_RUN_ID" =~ ^[a-z0-9][a-z0-9-]{5,30}$ ]] ||
    fail "M5D_RUN_ID must match [a-z0-9][a-z0-9-]{5,30}"
  require_env M5D_APP_HOST
  [[ "$M5D_APP_HOST" == "127.0.0.1" ]] || fail "M5D_APP_HOST must be 127.0.0.1"

  local name
  for name in M5D_APP1_PORT M5D_MANAGEMENT1_PORT M5D_APP2_PORT M5D_MANAGEMENT2_PORT \
      M5D_MYSQL_PORT M5D_REDIS_PORT M5D_ES_PORT M5D_RMQ_NAMESRV_PORT \
      M5D_RMQ_BROKER_PORT; do
    require_env "$name"
    require_port "$name"
  done
  require_env M5D_MYSQL_PASSWORD
  require_env M5D_REDIS_PASSWORD
  require_env M5D_RMQ_TOPIC
  require_env M5D_RMQ_CONSUMER_GROUP
  require_env M5D_RMQ_PRODUCER_GROUP
  require_env M5D_ES_SYNC_TOPIC
  require_env M5D_ES_SYNC_CONSUMER_GROUP

  local scoped
  for scoped in "$M5D_RMQ_TOPIC" "$M5D_RMQ_CONSUMER_GROUP" "$M5D_RMQ_PRODUCER_GROUP" \
      "$M5D_ES_SYNC_TOPIC" "$M5D_ES_SYNC_CONSUMER_GROUP"; do
    [[ "$scoped" == *"$M5D_RUN_ID"* ]] || fail "RocketMQ topic/groups must contain M5D_RUN_ID"
  done

  local ports=("$M5D_APP1_PORT" "$M5D_MANAGEMENT1_PORT" "$M5D_APP2_PORT"
    "$M5D_MANAGEMENT2_PORT" "$M5D_MYSQL_PORT" "$M5D_REDIS_PORT" "$M5D_ES_PORT"
    "$M5D_RMQ_NAMESRV_PORT" "$M5D_RMQ_BROKER_PORT" "$((M5D_RMQ_BROKER_PORT + 1))")
  local unique
  unique="$(printf '%s\n' "${ports[@]}" | sort -u | wc -l)"
  [[ "$unique" -eq "${#ports[@]}" ]] ||
    fail "all app, management and dependency ports, including Broker HA, must be unique"
}

assert_owned_container() {
  local name="$1"
  local label
  label="$(docker inspect --format "{{ index .Config.Labels \"${LABEL_KEY}\" }}" \
    "$name" 2>/dev/null || true)"
  [[ "$label" == "$M5D_RUN_ID" ]] || fail "container is not owned by this run: $name"
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
    -uroot "-p${M5D_MYSQL_PASSWORD}" --silent
}

redis_ready() {
  docker exec "$(container_name redis)" redis-cli \
    -a "$M5D_REDIS_PASSWORD" --no-auth-warning PING | grep -qx PONG
}

es_ready() {
  curl --fail --silent "http://127.0.0.1:${M5D_ES_PORT}/_cluster/health" >/dev/null
}

rmq_ready() {
  docker exec "$(container_name broker)" sh mqadmin clusterList \
    -n "$(container_name namesrv):9876" | grep -q "$(cluster_name)"
}

write_redis_sentinel() {
  docker exec "$(container_name redis)" redis-cli \
    -a "$M5D_REDIS_PASSWORD" --no-auth-warning \
    SET "m5d:sentinel:${M5D_RUN_ID}" "$M5D_RUN_ID" >/dev/null
}

verify_sentinels() {
  local mysql_value redis_value es_value
  mysql_value="$(docker exec "$(container_name mysql)" mysql -N -s \
    -uroot "-p${M5D_MYSQL_PASSWORD}" "$(schema_name)" -e "SELECT DATABASE()")"
  [[ "$mysql_value" == "$(schema_name)" ]] || fail "MySQL schema identity mismatch"
  redis_value="$(docker exec "$(container_name redis)" redis-cli \
    -a "$M5D_REDIS_PASSWORD" --no-auth-warning --raw \
    GET "m5d:sentinel:${M5D_RUN_ID}")"
  [[ "$redis_value" == "$M5D_RUN_ID" ]] || fail "Redis sentinel mismatch"
  es_value="$(curl --fail --silent "http://127.0.0.1:${M5D_ES_PORT}/" | \
    sed -n 's/.*"cluster_name"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')"
  [[ "$es_value" == "$(cluster_name)" ]] || fail "Elasticsearch cluster identity mismatch"
}

provision_rocketmq_scope() {
  local namesrv broker
  namesrv="$(container_name namesrv):9876"
  broker="$(container_name broker)"
  docker exec "$broker" sh mqadmin updateTopic -n "$namesrv" \
    -c "$(cluster_name)" -t "$M5D_RMQ_TOPIC" >/dev/null
  docker exec "$broker" sh mqadmin updateSubGroup -n "$namesrv" \
    -c "$(cluster_name)" -g "$M5D_RMQ_CONSUMER_GROUP" >/dev/null
  docker exec "$broker" sh mqadmin updateSubGroup -n "$namesrv" \
    -c "$(cluster_name)" -g "$M5D_RMQ_PRODUCER_GROUP" >/dev/null
  docker exec "$broker" sh mqadmin updateTopic -n "$namesrv" \
    -c "$(cluster_name)" -t "$M5D_ES_SYNC_TOPIC" >/dev/null
  docker exec "$broker" sh mqadmin updateSubGroup -n "$namesrv" \
    -c "$(cluster_name)" -g "$M5D_ES_SYNC_CONSUMER_GROUP" >/dev/null
}

verify_rocketmq_scope() {
  local namesrv broker consumer_config es_consumer_config
  namesrv="$(container_name namesrv):9876"
  broker="$(container_name broker)"
  wait_until "dedicated RocketMQ topic route" docker exec "$broker" sh mqadmin topicStatus \
    -n "$namesrv" -t "$M5D_RMQ_TOPIC"
  consumer_config="$(docker exec "$broker" sh mqadmin getConsumerConfig -n "$namesrv" \
    -g "$M5D_RMQ_CONSUMER_GROUP" 2>&1)"
  [[ "$consumer_config" == *"groupName"*"$M5D_RMQ_CONSUMER_GROUP"* ]] ||
    fail "dedicated consumer group missing"
  wait_until "dedicated ES sync topic route" docker exec "$broker" sh mqadmin topicStatus \
    -n "$namesrv" -t "$M5D_ES_SYNC_TOPIC"
  es_consumer_config="$(docker exec "$broker" sh mqadmin getConsumerConfig -n "$namesrv" \
    -g "$M5D_ES_SYNC_CONSUMER_GROUP" 2>&1)"
  [[ "$es_consumer_config" == *"groupName"*"$M5D_ES_SYNC_CONSUMER_GROUP"* ]] ||
    fail "dedicated ES sync consumer group missing"
}

up() {
  local network artifact_dir mysql_name redis_name es_name namesrv_name broker_name schema
  network="$(network_name)"
  artifact_dir="${PROJECT_DIR}/benchmark/m5d/${M5D_RUN_ID}"
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
  docker network inspect "$network" >/dev/null 2>&1 && fail "network already exists: $network"

  mkdir -p "$artifact_dir"
  docker network create --label "${LABEL_KEY}=${M5D_RUN_ID}" "$network" >/dev/null

  docker run -d --name "$mysql_name" --network "$network" \
    --label "${LABEL_KEY}=${M5D_RUN_ID}" \
    -p "127.0.0.1:${M5D_MYSQL_PORT}:3306" \
    -e MYSQL_ROOT_PASSWORD="$M5D_MYSQL_PASSWORD" -e MYSQL_DATABASE="$schema" \
    mysql:8.0 --default-authentication-plugin=mysql_native_password >/dev/null

  docker run -d --name "$redis_name" --network "$network" \
    --label "${LABEL_KEY}=${M5D_RUN_ID}" \
    -p "127.0.0.1:${M5D_REDIS_PORT}:6379" \
    redis:6.2 redis-server --save '' --appendonly no \
    --requirepass "$M5D_REDIS_PASSWORD" >/dev/null

  docker run -d --name "$es_name" --network "$network" \
    --label "${LABEL_KEY}=${M5D_RUN_ID}" \
    -p "127.0.0.1:${M5D_ES_PORT}:9200" \
    -e discovery.type=single-node -e "cluster.name=$(cluster_name)" \
    -e "ES_JAVA_OPTS=-Xms384m -Xmx384m" \
    "${M5D_ES_IMAGE:-hm-dianping-elasticsearch:latest}" >/dev/null

  docker run -d --name "$namesrv_name" --network "$network" \
    --label "${LABEL_KEY}=${M5D_RUN_ID}" \
    -p "127.0.0.1:${M5D_RMQ_NAMESRV_PORT}:9876" \
    apache/rocketmq:4.9.4 sh mqnamesrv >/dev/null

  local broker_conf="${artifact_dir}/broker.conf"
  {
    printf 'brokerClusterName=%s\n' "$(cluster_name)"
    printf 'brokerName=%s\n' "$(cluster_name)"
    printf 'brokerId=0\nbrokerIP1=127.0.0.1\n'
    printf 'listenPort=%s\nhaListenPort=%s\n' "$M5D_RMQ_BROKER_PORT" "$((M5D_RMQ_BROKER_PORT + 1))"
    printf 'namesrvAddr=%s:9876\n' "$namesrv_name"
    printf 'autoCreateTopicEnable=false\nautoCreateSubscriptionGroup=false\n'
    printf 'deleteWhen=04\nfileReservedTime=24\nbrokerRole=ASYNC_MASTER\nflushDiskType=ASYNC_FLUSH\n'
  } > "$broker_conf"
  docker run -d --name "$broker_name" --network "$network" \
    --label "${LABEL_KEY}=${M5D_RUN_ID}" \
    -p "127.0.0.1:${M5D_RMQ_BROKER_PORT}:${M5D_RMQ_BROKER_PORT}" \
    -p "127.0.0.1:$((M5D_RMQ_BROKER_PORT + 1)):$((M5D_RMQ_BROKER_PORT + 1))" \
    -v "${broker_conf}:/home/rocketmq/rocketmq-4.9.4/conf/m5d-broker.conf:ro" \
    apache/rocketmq:4.9.4 sh mqbroker \
    -c /home/rocketmq/rocketmq-4.9.4/conf/m5d-broker.conf >/dev/null

  wait_until MySQL mysql_ready
  wait_until Redis redis_ready
  wait_until Elasticsearch es_ready
  wait_until RocketMQ rmq_ready
  write_redis_sentinel
  provision_rocketmq_scope
  verify_sentinels
  verify_rocketmq_scope

  echo "M5D isolated stack is ready."
  echo "run_id=${M5D_RUN_ID} schema=${schema} cluster=$(cluster_name)"
  echo "apps=127.0.0.1:${M5D_APP1_PORT},127.0.0.1:${M5D_APP2_PORT} management=127.0.0.1:${M5D_MANAGEMENT1_PORT},127.0.0.1:${M5D_MANAGEMENT2_PORT}"
  echo "mysql=127.0.0.1:${M5D_MYSQL_PORT} redis=127.0.0.1:${M5D_REDIS_PORT} elasticsearch=127.0.0.1:${M5D_ES_PORT}"
  echo "ROCKETMQ_NAME_SERVER=127.0.0.1:${M5D_RMQ_NAMESRV_PORT} seckill_topic=${M5D_RMQ_TOPIC} seckill_consumer_group=${M5D_RMQ_CONSUMER_GROUP} producer_group=${M5D_RMQ_PRODUCER_GROUP} es_sync_topic=${M5D_ES_SYNC_TOPIC} es_sync_consumer_group=${M5D_ES_SYNC_CONSUMER_GROUP}"
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
  verify_rocketmq_scope
  echo "M5D isolation checks passed."
}

dependency_action() {
  local dependency="$1" action="$2" name
  name="$(container_name "$dependency")"
  assert_owned_container "$name"
  case "${dependency}:${action}" in
    redis:pause|es:pause) docker pause "$name" >/dev/null ;;
    redis:unpause|es:unpause) docker unpause "$name" >/dev/null ;;
    redis:stop|mysql:stop|es:stop|broker:stop) docker stop --time 10 "$name" >/dev/null ;;
    redis:start)
      docker start "$name" >/dev/null
      wait_until Redis redis_ready
      write_redis_sentinel
      ;;
    mysql:start) docker start "$name" >/dev/null; wait_until MySQL mysql_ready ;;
    es:start) docker start "$name" >/dev/null; wait_until Elasticsearch es_ready ;;
    broker:start)
      docker start "$name" >/dev/null
      wait_until RocketMQ rmq_ready
      verify_rocketmq_scope
      ;;
    *) fail "unsupported action: ${dependency}-${action}" ;;
  esac
  echo "${dependency} ${action} completed for owned container ${name}."
}

consumer_action() {
  local group="$1" enabled="$2" broker namesrv config
  broker="$(container_name broker)"
  namesrv="$(container_name namesrv):9876"
  assert_owned_container "$broker"
  docker exec "$broker" sh mqadmin updateSubGroup -n "$namesrv" \
    -c "$(cluster_name)" -g "$group" -s "$enabled" >/dev/null
  config="$(docker exec "$broker" sh mqadmin getConsumerConfig -n "$namesrv" \
    -g "$group" 2>&1)"
  [[ "$config" == *"consumeEnable"*"$enabled"* ]] || fail "consumer state did not become ${enabled}"
  echo "Consumer group ${group} consumeEnable=${enabled}."
}

consumer_progress() {
  local group="$1" broker namesrv
  broker="$(container_name broker)"
  namesrv="$(container_name namesrv):9876"
  assert_owned_container "$broker"
  docker exec "$broker" sh mqadmin consumerProgress -n "$namesrv" \
    -g "$group"
}

down() {
  local name resolved network residual=0
  for name in mysql redis es namesrv broker; do
    resolved="$(container_name "$name")"
    if docker inspect "$resolved" >/dev/null 2>&1; then
      assert_owned_container "$resolved"
    fi
  done
  for name in broker namesrv es redis mysql; do
    resolved="$(container_name "$name")"
    if docker inspect "$resolved" >/dev/null 2>&1; then
      docker rm -f "$resolved" >/dev/null || residual=1
    fi
  done
  network="$(network_name)"
  if docker network inspect "$network" >/dev/null 2>&1; then
    local label
    label="$(docker network inspect --format "{{ index .Labels \"${LABEL_KEY}\" }}" "$network")"
    [[ "$label" == "$M5D_RUN_ID" ]] || fail "network ownership mismatch: $network"
    docker network rm "$network" >/dev/null || residual=1
  fi
  if docker ps -a --filter "label=${LABEL_KEY}=${M5D_RUN_ID}" --format '{{.Names}}' | grep -q .; then
    residual=1
  fi
  docker network ls --filter "label=${LABEL_KEY}=${M5D_RUN_ID}" --format '{{.Name}}' | grep -q . && residual=1
  echo "Removed only resources labelled for M5D run ${M5D_RUN_ID}; container data is not recoverable."
  [[ "$residual" -eq 0 ]] || fail "M5D run has residual resources; manual inspection required"
  echo "Residual check passed: no container or network remains for this run-id."
}

require_cmd docker
require_cmd curl
require_isolation

case "$ACTION" in
  up) up ;;
  status) status ;;
  redis-pause) dependency_action redis pause ;;
  redis-unpause) dependency_action redis unpause ;;
  redis-stop) dependency_action redis stop ;;
  redis-start) dependency_action redis start ;;
  mysql-stop) dependency_action mysql stop ;;
  mysql-start) dependency_action mysql start ;;
  es-pause) dependency_action es pause ;;
  es-unpause) dependency_action es unpause ;;
  es-stop) dependency_action es stop ;;
  es-start) dependency_action es start ;;
  broker-stop) dependency_action broker stop ;;
  broker-start) dependency_action broker start ;;
  consumer-pause) consumer_action "$M5D_RMQ_CONSUMER_GROUP" false ;;
  consumer-resume) consumer_action "$M5D_RMQ_CONSUMER_GROUP" true ;;
  es-consumer-pause) consumer_action "$M5D_ES_SYNC_CONSUMER_GROUP" false ;;
  es-consumer-resume) consumer_action "$M5D_ES_SYNC_CONSUMER_GROUP" true ;;
  consumer-progress) consumer_progress "${2:-$M5D_RMQ_CONSUMER_GROUP}" ;;
  down) down ;;
  *)
    echo "Usage: M5D_ISOLATED=true M5D_RUN_ID=... M5D_*_PORT=... $0 up|status|redis-pause|redis-unpause|redis-stop|redis-start|mysql-stop|mysql-start|es-pause|es-unpause|es-stop|es-start|broker-stop|broker-start|consumer-pause|consumer-resume|es-consumer-pause|es-consumer-resume|consumer-progress [group]|down" >&2
    exit 2
    ;;
esac

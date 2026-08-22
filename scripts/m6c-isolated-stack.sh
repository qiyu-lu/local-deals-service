#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ACTION="${1:-}"
LABEL_KEY="com.localdeals.m6c.run-id"
ROLE_KEY="com.localdeals.m6c.role"

fail() {
  echo "M6C isolation gate failed: $*" >&2
  exit 1
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || fail "missing command: $1"
}

require_env() {
  [[ -n "${!1:-}" ]] || fail "$1 must be set explicitly"
}

require_isolation() {
  [[ "${M6C_ISOLATED:-}" == "true" ]] || fail "set M6C_ISOLATED=true"
  require_env M6C_RUN_ID
  [[ "$M6C_RUN_ID" =~ ^m6c_[a-z0-9][a-z0-9_-]{2,40}$ ]] ||
    fail "M6C_RUN_ID must match m6c_[a-z0-9][a-z0-9_-]{2,40}"
  require_env M6C_MYSQL_PORT
  require_env M6C_REDIS_PORT
  require_env M6C_MYSQL_PASSWORD
  [[ "$M6C_MYSQL_PORT" =~ ^[0-9]+$ ]] || fail "M6C_MYSQL_PORT must be numeric"
  [[ "$M6C_REDIS_PORT" =~ ^[0-9]+$ ]] || fail "M6C_REDIS_PORT must be numeric"
  (( M6C_MYSQL_PORT >= 1024 && M6C_MYSQL_PORT <= 65535 && M6C_MYSQL_PORT != 3306 )) ||
    fail "M6C_MYSQL_PORT must be an unprivileged non-3306 port"
  (( M6C_REDIS_PORT >= 1024 && M6C_REDIS_PORT <= 65535 && M6C_REDIS_PORT != 6379 &&
     M6C_REDIS_PORT != 3306 && M6C_REDIS_PORT != M6C_MYSQL_PORT )) ||
    fail "M6C_REDIS_PORT must be a distinct non-shared port"
}

container_name() {
  printf 'm6c-%s-%s\n' "$M6C_RUN_ID" "$1"
}

network_name() {
  printf 'm6c-%s-net\n' "$M6C_RUN_ID"
}

schema_name() {
  printf '%s_app\n' "${M6C_RUN_ID//-/_}"
}

sentinel_schema() {
  printf '%s_sentinel\n' "${M6C_RUN_ID//-/_}"
}

assert_owned_container() {
  local name="$1" role="$2" run_id role_value
  run_id="$(docker inspect --format "{{index .Config.Labels \"${LABEL_KEY}\"}}" "$name" 2>/dev/null || true)"
  role_value="$(docker inspect --format "{{index .Config.Labels \"${ROLE_KEY}\"}}" "$name" 2>/dev/null || true)"
  [[ "$run_id" == "$M6C_RUN_ID" && "$role_value" == "$role" ]] ||
    fail "resource ownership mismatch: ${name} run=${run_id} role=${role_value}"
}

assert_owned_network() {
  local network="$1" run_id
  run_id="$(docker network inspect --format "{{index .Labels \"${LABEL_KEY}\"}}" "$network" 2>/dev/null || true)"
  [[ "$run_id" == "$M6C_RUN_ID" ]] || fail "network ownership mismatch: ${network}"
}

mysql_ready() {
  docker exec "$(container_name mysql)" mysql -uroot "-p${M6C_MYSQL_PASSWORD}" \
    -e 'SELECT 1' >/dev/null
}

redis_ready() {
  docker exec "$(container_name redis)" redis-cli PING | grep -qx PONG
}

wait_until() {
  local description="$1"; shift
  for attempt in $(seq 1 90); do
    if "$@" >/dev/null 2>&1; then return 0; fi
    sleep 2
  done
  fail "timed out waiting for ${description}"
}

provision_sentinels() {
  docker exec "$(container_name mysql)" mysql -uroot "-p${M6C_MYSQL_PASSWORD}" -e \
    "CREATE DATABASE IF NOT EXISTS \`$(sentinel_schema)\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci; \
     CREATE TABLE IF NOT EXISTS \`$(sentinel_schema)\`.m6c_run_sentinel \
       (run_id varchar(64) NOT NULL PRIMARY KEY, purpose varchar(64) NOT NULL); \
     DELETE FROM \`$(sentinel_schema)\`.m6c_run_sentinel; \
     INSERT INTO \`$(sentinel_schema)\`.m6c_run_sentinel(run_id,purpose) \
       VALUES ('${M6C_RUN_ID}','m6c-isolated-mysql');" >/dev/null
  docker exec "$(container_name redis)" redis-cli SET \
    "m6c:sentinel:${M6C_RUN_ID}" "$M6C_RUN_ID" >/dev/null
}

verify_sentinels() {
  local mysql_value redis_value
  mysql_value="$(docker exec "$(container_name mysql)" mysql -N -s \
    -uroot "-p${M6C_MYSQL_PASSWORD}" "$(schema_name)" -e 'SELECT DATABASE()')"
  [[ "$mysql_value" == "$(schema_name)" ]] || fail "MySQL app schema identity mismatch"
  redis_value="$(docker exec "$(container_name redis)" redis-cli --raw \
    GET "m6c:sentinel:${M6C_RUN_ID}")"
  [[ "$redis_value" == "$M6C_RUN_ID" ]] || fail "Redis sentinel mismatch"
  local mysql_sentinel
  mysql_sentinel="$(docker exec "$(container_name mysql)" mysql -N -s \
    -uroot "-p${M6C_MYSQL_PASSWORD}" "$(sentinel_schema)" -e \
    "SELECT run_id FROM m6c_run_sentinel WHERE purpose='m6c-isolated-mysql'")"
  [[ "$mysql_sentinel" == "$M6C_RUN_ID" ]] || fail "MySQL sentinel mismatch"
}

up() {
  local network="$(network_name)" mysql_name="$(container_name mysql)" redis_name="$(container_name redis)"
  local schema="$(schema_name)"
  [[ "$schema" != "local_deals" && "$schema" != "hmdp" ]] || fail "unsafe app schema"
  for name in "$mysql_name" "$redis_name"; do
    if docker inspect "$name" >/dev/null 2>&1; then fail "container already exists: $name"; fi
  done
  if docker network inspect "$network" >/dev/null 2>&1; then fail "network already exists: $network"; fi

  docker network create --label "${LABEL_KEY}=${M6C_RUN_ID}" "$network" >/dev/null
  docker run -d --name "$mysql_name" --network "$network" \
    --label "${LABEL_KEY}=${M6C_RUN_ID}" --label "${ROLE_KEY}=mysql" \
    -p "127.0.0.1:${M6C_MYSQL_PORT}:3306" \
    -e MYSQL_ROOT_PASSWORD="$M6C_MYSQL_PASSWORD" -e MYSQL_DATABASE="$schema" \
    mysql:8.0 --default-authentication-plugin=mysql_native_password >/dev/null
  docker run -d --name "$redis_name" --network "$network" \
    --label "${LABEL_KEY}=${M6C_RUN_ID}" --label "${ROLE_KEY}=redis" \
    -p "127.0.0.1:${M6C_REDIS_PORT}:6379" \
    redis:7.2-alpine redis-server --save '' --appendonly no >/dev/null

  wait_until MySQL mysql_ready
  wait_until Redis redis_ready
  provision_sentinels
  verify_sentinels
  echo "M6C isolated stack is ready."
  echo "run_id=${M6C_RUN_ID} app_schema=$(schema_name) sentinel_schema=$(sentinel_schema)"
  echo "mysql=127.0.0.1:${M6C_MYSQL_PORT} redis=127.0.0.1:${M6C_REDIS_PORT}"
  echo "No RocketMQ or Elasticsearch containers were started."
}

status() {
  local mysql_name="$(container_name mysql)" redis_name="$(container_name redis)"
  assert_owned_container "$mysql_name" mysql
  assert_owned_container "$redis_name" redis
  assert_owned_network "$(network_name)"
  docker inspect --format '{{.Name}} {{.State.Status}}' "$mysql_name" "$redis_name"
  mysql_ready
  redis_ready
  verify_sentinels
  echo "M6C isolation checks passed."
}

env_output() {
  cat <<EOF
M6C_ISOLATED=true
M6C_RUN_ID=${M6C_RUN_ID}
M6C_MYSQL_PORT=${M6C_MYSQL_PORT}
M6C_REDIS_PORT=${M6C_REDIS_PORT}
M6C_MYSQL_BASE_URL=jdbc:mysql://127.0.0.1:${M6C_MYSQL_PORT}/?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai
M6C_MYSQL_USERNAME=root
M6C_REDIS_HOST=127.0.0.1
LOCAL_DEALS_DATASOURCE_URL=jdbc:mysql://127.0.0.1:${M6C_MYSQL_PORT}/$(schema_name)?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai
LOCAL_DEALS_DATASOURCE_USERNAME=root
EOF
}

validate_strace() {
  local trace="$1" line port address
  [[ -s "$trace" ]] || fail "strace output is empty: $trace"
  while IFS= read -r line; do
    [[ "$line" == *"connect("*"AF_INET"* ]] || continue
    if [[ "$line" =~ sin_port=htons\(([0-9]+)\) ]]; then
      port="${BASH_REMATCH[1]}"
    elif [[ "$line" =~ sin6_port=htons\(([0-9]+)\) ]]; then
      port="${BASH_REMATCH[1]}"
    else
      fail "unparsed IPv4 network target in strace: $line"
    fi
    if [[ "$line" =~ inet_addr\(\"([^\"]+)\"\) ]]; then
      address="${BASH_REMATCH[1]}"
    elif [[ "$line" == *'inet_pton(AF_INET6, "::ffff:127.0.0.1"'* ]]; then
      address="127.0.0.1"
    else
      fail "unparsed IPv4 address in strace: $line"
    fi
    [[ "$address" == "127.0.0.1" ]] || fail "unknown IPv4 target ${address}:${port}"
    [[ "$port" == "$M6C_MYSQL_PORT" || "$port" == "$M6C_REDIS_PORT" ]] ||
      fail "non-M6C TCP target 127.0.0.1:${port}"
  done < "$trace"
  if rg -n 'connect\(.*(3306|6379|9876|10911|9200)' "$trace" >/dev/null; then
    fail "forbidden shared/default port observed in strace"
  fi
  echo "M6C strace network allow-list passed: only MySQL ${M6C_MYSQL_PORT}, Redis ${M6C_REDIS_PORT}, and AF_UNIX."
}

run_tests() {
  require_cmd strace
  local evidence_dir="${M6C_EVIDENCE_DIR:-/tmp/m6c-${M6C_RUN_ID}}"
  mkdir -p "$evidence_dir"
  local trace="${evidence_dir}/m6c-java-network.strace"
  local log="${evidence_dir}/m6c-tests.log"
  local java_home="${JAVA_HOME:-/home/sd101t/.jdks/dragonwell-ex-1.8.0_472}"
  [[ -x "$java_home/bin/java" ]] || fail "Java 8 JAVA_HOME is required: $java_home"
  env M6C_ISOLATED=true M6C_RUN_ID="$M6C_RUN_ID" \
    M6C_MYSQL_PORT="$M6C_MYSQL_PORT" M6C_REDIS_PORT="$M6C_REDIS_PORT" \
    M6C_MYSQL_BASE_URL="jdbc:mysql://127.0.0.1:${M6C_MYSQL_PORT}/?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai" \
    M6C_MYSQL_USERNAME=root M6C_MYSQL_PASSWORD="$M6C_MYSQL_PASSWORD" \
    LOCAL_DEALS_DATASOURCE_URL="jdbc:mysql://127.0.0.1:${M6C_MYSQL_PORT}/$(schema_name)?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai" \
    LOCAL_DEALS_DATASOURCE_USERNAME=root LOCAL_DEALS_DATASOURCE_PASSWORD="$M6C_MYSQL_PASSWORD" \
    JAVA_HOME="$java_home" PATH="$java_home/bin:$PATH" \
    strace -f -e trace=network -o "$trace" \
    /opt/idea/plugins/maven/lib/maven3/bin/mvn -o \
      -Dtest=M6cFlywayIT,M6cBatchBusinessIT,M6cRedisRecoveryIT test 2>&1 | tee "$log"
  validate_strace "$trace"
  echo "M6C evidence_dir=${evidence_dir}"
}

down() {
  local name network
  for name in mysql redis; do
    if docker inspect "$(container_name "$name")" >/dev/null 2>&1; then
      assert_owned_container "$(container_name "$name")" "$name"
    fi
  done
  network="$(network_name)"
  if docker network inspect "$network" >/dev/null 2>&1; then assert_owned_network "$network"; fi
  for name in redis mysql; do
    if docker inspect "$(container_name "$name")" >/dev/null 2>&1; then
      docker rm -fv "$(container_name "$name")" >/dev/null
    fi
  done
  if docker network inspect "$network" >/dev/null 2>&1; then docker network rm "$network" >/dev/null; fi
  echo "Removed only M6C resources owned by run ${M6C_RUN_ID}."
}

require_cmd docker
require_isolation

case "$ACTION" in
  up) up ;;
  status) status ;;
  env) env_output ;;
  run-tests) run_tests ;;
  down) down ;;
  *) echo "Usage: M6C_ISOLATED=true M6C_RUN_ID=m6c_... M6C_MYSQL_PORT=... M6C_REDIS_PORT=... M6C_MYSQL_PASSWORD=... $0 up|status|env|run-tests|down" >&2; exit 2 ;;
esac

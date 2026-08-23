#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GROUP="${1:-}"
RUN_ID="${M7RC_RUN_ID:-m7rc_20260823a}"
PROJECT="${M7RC_PROJECT:-m7rc-${RUN_ID#m7rc_}}"
MYSQL_PORT="${M7RC_MYSQL_PORT:-28330}"
REDIS_PORT="${M7RC_REDIS_PORT:-28379}"
RMQ_NAMESRV_PORT="${M7RC_RMQ_NAMESRV_PORT:-29876}"
ES_PORT="${M7RC_ES_PORT:-29200}"
MYSQL_PASSWORD="${M7RC_MYSQL_PASSWORD:-M7rcOnly-20260823}"
REDIS_PASSWORD="${M7RC_REDIS_PASSWORD:-M7rcRedis-20260823}"
SCHEMA="${M7RC_SCHEMA:-m5b_${RUN_ID//-/_}}"
JAVA8_HOME="${JAVA8_HOME:-/home/sd101t/.jdks/dragonwell-ex-1.8.0_472}"
MAVEN_CMD="${MAVEN_CMD:-/home/sd101t/.m2/wrapper/dists/apache-maven-3.9.11/a2d47e15/bin/mvn}"
ARTIFACT_DIR="${M7RC_ARTIFACT_DIR:-${PROJECT_DIR}/benchmark/pre-m8/${RUN_ID}}"
TARGET="${M7_RC_BATCH_TARGET:-100}"
BATCH_SIZE="${M7_RC_BATCH_BATCH_SIZE:-17}"

case "$GROUP" in
  auth)
    TEST_CLASSES=(AdminRbacIT MarketingAdminIsolationIT MarketingMvcSecurityTest)
    ;;
  marketing)
    TEST_CLASSES=(M6aBusinessFlowIT MarketingGrantConcurrencyIT M6bDailyTaskBusinessIT M6cBatchBusinessIT)
    ;;
  flyway)
    TEST_CLASSES=(M6cFlywayIT)
    ;;
  seckill)
    TEST_CLASSES=(SeckillWithRocketMQIT \
      'SeckillOrderRetryIT#transientFailure_isRedeliveredByBroker' \
      'SeckillOrderRetryIT#permanentFailure_isNotRedelivered' \
      SeckillOrderStateIT VoucherOrderReliabilityIT)
    ;;
  core)
    TEST_CLASSES=(BoundedCacheMySqlRedisIT BlogLikeReliabilityIT BlogHotRankRedisIT CanalSyncIT)
    ;;
  *)
    echo "Usage: M7RC_RUN_ID=... $0 {auth|marketing|flyway|seckill|core}" >&2
    exit 2
    ;;
esac

mkdir -p "${ARTIFACT_DIR}/tests"
LOG="${ARTIFACT_DIR}/tests/${GROUP}.log"
STATUS_FILE="${ARTIFACT_DIR}/tests/${GROUP}.status"
DATASOURCE_URL="jdbc:mysql://127.0.0.1:${MYSQL_PORT}/${SCHEMA}?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai"
MYSQL_BASE_URL="jdbc:mysql://127.0.0.1:${MYSQL_PORT}/?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai"
M6A_RUN_ID="m6a_${RUN_ID}"
M6B_RUN_ID="m6b_${RUN_ID}"
M6C_RUN_ID="m6c_${RUN_ID}"

set +e
: > "$LOG"
STATUS=0
for TEST_CLASS in "${TEST_CLASSES[@]}"; do
  CLASS_TOKEN="$(printf '%s' "$TEST_CLASS" | tr '[:upper:]' '[:lower:]' | sed 's/[^a-z0-9_-]/_/g')"
  CLASS_SECKILL_CONSUMER_GROUP="${M7RC_SECKILL_CONSUMER_GROUP:-${RUN_ID}_${CLASS_TOKEN}_it}"
  CLASS_SECKILL_TOPIC="${M7RC_SECKILL_TOPIC:-${RUN_ID}_${CLASS_TOKEN}_topic}"
  if [[ "$GROUP" == "seckill" ]]; then
    docker exec "${RUN_ID}-broker" sh mqadmin updateTopic \
      -n "${RUN_ID}-namesrv:9876" -c "$PROJECT" -t "$CLASS_SECKILL_TOPIC" >/dev/null
    docker exec "${RUN_ID}-broker" sh mqadmin updateSubGroup \
      -n "${RUN_ID}-namesrv:9876" -c "$PROJECT" -g "$CLASS_SECKILL_CONSUMER_GROUP" >/dev/null
  fi
  printf '\n===== M7-RC test class=%s =====\n' "$TEST_CLASS" | tee -a "$LOG"
  env \
    JAVA_HOME="$JAVA8_HOME" PATH="$JAVA8_HOME/bin:$PATH" \
    LOCAL_DEALS_DATASOURCE_URL="$DATASOURCE_URL" \
    LOCAL_DEALS_DATASOURCE_USERNAME=root LOCAL_DEALS_DATASOURCE_PASSWORD="$MYSQL_PASSWORD" \
    LOCAL_DEALS_REDIS_HOST=127.0.0.1 LOCAL_DEALS_REDIS_PORT="$REDIS_PORT" \
    LOCAL_DEALS_REDIS_PASSWORD="$REDIS_PASSWORD" \
    M5A_ISOLATED=true M5B_ISOLATED=true M5B_RUN_ID="$RUN_ID" \
    M5B_MYSQL_URL="$DATASOURCE_URL" M5B_MYSQL_USER=root M5B_MYSQL_PASSWORD="$MYSQL_PASSWORD" \
    M5B_REDIS_HOST=127.0.0.1 M5B_REDIS_PORT="$REDIS_PORT" M5B_REDIS_PASSWORD="$REDIS_PASSWORD" \
    M5B_ES_PORT="$ES_PORT" M5B_RMQ_NAMESRV_PORT="$RMQ_NAMESRV_PORT" \
    M6A_ISOLATED=true M6A_RUN_ID="$M6A_RUN_ID" M6A_MYSQL_PORT="$MYSQL_PORT" \
    M6A_MYSQL_BASE_URL="$MYSQL_BASE_URL" M6A_MYSQL_USERNAME=root M6A_MYSQL_PASSWORD="$MYSQL_PASSWORD" \
    M6B_ISOLATED=true M6B_RUN_ID="$M6B_RUN_ID" M6B_MYSQL_PORT="$MYSQL_PORT" \
    M6B_MYSQL_BASE_URL="$MYSQL_BASE_URL" M6B_MYSQL_USERNAME=root M6B_MYSQL_PASSWORD="$MYSQL_PASSWORD" \
    M6C_ISOLATED=true M6C_RUN_ID="$M6C_RUN_ID" M6C_MYSQL_PORT="$MYSQL_PORT" M6C_REDIS_PORT="$REDIS_PORT" \
    M6C_REDIS_PASSWORD="$REDIS_PASSWORD" \
    M6C_MYSQL_BASE_URL="$MYSQL_BASE_URL" M6C_MYSQL_USERNAME=root M6C_MYSQL_PASSWORD="$MYSQL_PASSWORD" \
    LOCAL_DEALS_RUN_ISOLATED_LIKE_IT=true \
    LOCAL_DEALS_SECKILL_CONSUMER_GROUP="$CLASS_SECKILL_CONSUMER_GROUP" \
    LOCAL_DEALS_SECKILL_TOPIC="$CLASS_SECKILL_TOPIC" \
    M7_RC_BATCH_TARGET="$TARGET" M7_RC_BATCH_BATCH_SIZE="$BATCH_SIZE" \
    "$MAVEN_CMD" -o -q \
      -Dspring.datasource.url="$DATASOURCE_URL" \
      -Dspring.datasource.username=root -Dspring.datasource.password="$MYSQL_PASSWORD" \
      -Dspring.redis.host=127.0.0.1 -Dspring.redis.port="$REDIS_PORT" \
      -Dspring.redis.password="$REDIS_PASSWORD" \
      -Dspring.elasticsearch.rest.uris="http://127.0.0.1:${ES_PORT}" \
      -Drocketmq.name-server="127.0.0.1:${RMQ_NAMESRV_PORT}" \
      "-Drocketmq.consumer.listeners[${CLASS_SECKILL_CONSUMER_GROUP}][seckill-order-topic]=true" \
      "-Drocketmq.consumer.listeners[${CLASS_SECKILL_CONSUMER_GROUP}][${CLASS_SECKILL_TOPIC}]=true" \
      -Dlocal-deals.seckill.topic="$CLASS_SECKILL_TOPIC" \
      -Dm7rc.seckill.retry.topic="$CLASS_SECKILL_TOPIC" \
      -Dtest="$TEST_CLASS" test 2>&1 | tee -a "$LOG"
  CLASS_STATUS="${PIPESTATUS[0]}"
  printf 'M7-RC test class=%s status=%s\n' "$TEST_CLASS" "$CLASS_STATUS" | tee -a "$LOG"
  if [[ "$CLASS_STATUS" -ne 0 ]]; then STATUS=1; fi
done
set -e
printf '%s\n' "$STATUS" > "$STATUS_FILE"
echo "M7-RC test group=${GROUP} status=${STATUS} log=${LOG}"
exit "$STATUS"

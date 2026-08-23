# Seckill Reliability Check

- run_id: 2026-05-20-seckill-reliable-stream-v1-fault-injection-r1
- date: 2026-05-20
- scenario: seckill-reliability
- implementation: reliable-stream-v1
- expectation: current
- project_dir: /home/sd101t/IdeaProjects/hm-dianping
- output_root: /home/sd101t/IdeaProjects/hm-dianping
- mysql_container: hmdp-mysql
- mysql_database: local_deals
- redis_container: hmdp-redis
- stream_key: stream.orders
- stream_group: g1
- dead_letter_key: stream.orders.dlq
- voucher_id: 10
- fault_user_id: 1
- injected_record_id: 1779243827418-0
- malformed_payload: userId=1, voucherId=10, missing orderId
- wait_ms: 15000
- poll_ms: 200
- stream_len: 1
- stream_pending: 0
- stream_dead_letters: 1
- retry_key_count: 0
- correctness: pass
- reset_log: docs/reliability-results/2026-05-20-seckill-reliable-stream-v1-fault-injection-r1-reset.log
- metrics_csv: docs/reliability-results/metrics.csv

## Dead Letter Sample

```text
1779243829508-0
sourceStream
stream.orders
sourceGroup
g1
sourceRecordId
1779243827418-0
retries
3
errorClass
java.lang.IllegalArgumentException
errorMessage
Missing stream field: orderId
payload.userId
1
payload.voucherId
10
```

## Markdown Row

| 2026-05-20 | reliable-stream-v1 | current | 10 | 1779243827418-0 | 0 | 1 | 0 | pass | [run-summary](2026-05-20-seckill-reliable-stream-v1-fault-injection-r1.md) |

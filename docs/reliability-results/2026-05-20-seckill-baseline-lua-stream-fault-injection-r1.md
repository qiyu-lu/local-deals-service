# Seckill Reliability Check

- run_id: 2026-05-20-seckill-baseline-lua-stream-fault-injection-r1
- date: 2026-05-20
- scenario: seckill-reliability
- implementation: baseline-lua-stream
- expectation: baseline
- project_dir: /home/sd101t/IdeaProjects/hm-dianping-baseline
- output_root: /home/sd101t/IdeaProjects/hm-dianping
- mysql_container: hmdp-mysql
- mysql_database: hmdp
- redis_container: hmdp-redis
- stream_key: stream.orders
- stream_group: g1
- dead_letter_key: stream.orders.dlq
- voucher_id: 11
- fault_user_id: 1
- injected_record_id: 1779241499797-0
- malformed_payload: userId=1, voucherId=11, missing orderId
- wait_ms: 15000
- poll_ms: 200
- stream_len: 2
- stream_pending: 1
- stream_dead_letters: 0
- retry_key_count: 0
- correctness: pass
- reset_log: docs/reliability-results/2026-05-20-seckill-baseline-lua-stream-fault-injection-r1-reset.log
- metrics_csv: docs/reliability-results/metrics.csv

## Dead Letter Sample

```text

```

## Markdown Row

| 2026-05-20 | baseline-lua-stream | baseline | 11 | 1779241499797-0 | 1 | 0 | 0 | pass | [run-summary](2026-05-20-seckill-baseline-lua-stream-fault-injection-r1.md) |

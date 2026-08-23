# Seckill Benchmark Run Summary

- run_id: 2026-05-19-seckill-reliable-stream-v1-1000t-1l-r1
- date: 2026-05-19
- scenario: seckill-reliable-v1
- implementation: reliable-stream-v1
- voucher_id: 10
- stream_key: stream.orders
- stream_group: g1
- dead_letter_key: stream.orders.dlq
- stock: 1000
- expected_orders: 1000
- threads: 1000
- loops: 1
- ramp_up_seconds: 5
- total_requests: 1000
- samples: 1000
- throughput: 208.33333
- avg_ms: 2
- median_ms: 1
- p90_ms: 2
- p95_ms: 2
- p99_ms: 29
- max_ms: 32
- error_pct: 0.000%
- jmeter_elapsed_ms: 7213
- drain_ms: 72
- poll_interval_ms: 50
- java_home: /home/sd101t/.jdks/dragonwell-ex-1.8.0_472
- maven_cmd: /opt/idea/plugins/maven/lib/maven3/bin/mvn
- mysql_orders: 1000
- mysql_stock: 0
- duplicate_orders: 0
- redis_stock: 0
- redis_order_count: 1000
- stream_len: 1000
- stream_pending: 0
- stream_dead_letters: 0
- correctness: pass
- metrics_csv: docs/archive/redis-stream/JmeterTestSummary/seckill-reliable-v1/metrics.csv
- jtl_file: benchmark/2026-05-19-seckill-reliable-stream-v1-1000t-1l-r1.jtl
- summary_csv: docs/archive/redis-stream/JmeterTestSummary/seckill-reliable-v1/2026-05-19-seckill-reliable-stream-v1-1000t-1l-summary-r1.csv
- aggregate_csv: docs/archive/redis-stream/JmeterTestSummary/seckill-reliable-v1/2026-05-19-seckill-reliable-stream-v1-1000t-1l-aggregate-r1.csv
- html_report: benchmark/report-2026-05-19-seckill-reliable-stream-v1-1000t-1l-r1

## Markdown Row

| 2026-05-19 | reliable-stream-v1 | seckill-reliable-v1 | 1000 线程 / 1 次循环 | 1000 | 1000 | 208.33333 | 2 / 29 | 72 | 1000 / 1000 | 0 | 0 | pass | [run-summary](2026-05-19-seckill-reliable-stream-v1-1000t-1l-r1-run-summary.md) |

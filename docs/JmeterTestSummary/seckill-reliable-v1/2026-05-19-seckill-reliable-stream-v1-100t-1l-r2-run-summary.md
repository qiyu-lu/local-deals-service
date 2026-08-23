# Seckill Benchmark Run Summary

- run_id: 2026-05-19-seckill-reliable-stream-v1-100t-1l-r2
- date: 2026-05-19
- scenario: seckill-reliable-v1
- implementation: reliable-stream-v1
- voucher_id: 10
- stream_key: stream.orders
- stream_group: g1
- dead_letter_key: stream.orders.dlq
- stock: 100
- expected_orders: 100
- threads: 100
- loops: 1
- ramp_up_seconds: 5
- total_requests: 100
- samples: 100
- throughput: 20.59732
- avg_ms: 3
- median_ms: 3
- p90_ms: 3
- p95_ms: 3
- p99_ms: 20
- max_ms: 20
- error_pct: 0.000%
- jmeter_elapsed_ms: 7185
- drain_ms: 67
- poll_interval_ms: 50
- java_home: /home/sd101t/.jdks/dragonwell-ex-1.8.0_472
- maven_cmd: /opt/idea/plugins/maven/lib/maven3/bin/mvn
- mysql_orders: 100
- mysql_stock: 0
- duplicate_orders: 0
- redis_stock: 0
- redis_order_count: 100
- stream_len: 100
- stream_pending: 0
- stream_dead_letters: 0
- correctness: pass
- metrics_csv: docs/JmeterTestSummary/seckill-reliable-v1/metrics.csv
- jtl_file: benchmark/2026-05-19-seckill-reliable-stream-v1-100t-1l-r2.jtl
- summary_csv: docs/JmeterTestSummary/seckill-reliable-v1/2026-05-19-seckill-reliable-stream-v1-100t-1l-summary-r2.csv
- aggregate_csv: docs/JmeterTestSummary/seckill-reliable-v1/2026-05-19-seckill-reliable-stream-v1-100t-1l-aggregate-r2.csv
- html_report: benchmark/report-2026-05-19-seckill-reliable-stream-v1-100t-1l-r2

## Markdown Row

| 2026-05-19 | reliable-stream-v1 | seckill-reliable-v1 | 100 线程 / 1 次循环 | 100 | 100 | 20.59732 | 3 / 20 | 67 | 100 / 100 | 0 | 0 | pass | [run-summary](2026-05-19-seckill-reliable-stream-v1-100t-1l-r2-run-summary.md) |

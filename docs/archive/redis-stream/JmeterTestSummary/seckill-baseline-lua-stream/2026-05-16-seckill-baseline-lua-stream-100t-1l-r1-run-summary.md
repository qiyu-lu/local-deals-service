# Seckill Benchmark Run Summary

- run_id: 2026-05-16-seckill-baseline-lua-stream-100t-1l-r1
- date: 2026-05-16
- scenario: seckill-baseline-lua-stream
- implementation: baseline-lua-stream
- voucher_id: 11
- stock: 100
- expected_orders: 100
- threads: 100
- loops: 1
- ramp_up_seconds: 5
- total_requests: 100
- samples: 100
- throughput: 20.49180
- avg_ms: 2
- median_ms: 2
- p90_ms: 2
- p95_ms: 2
- p99_ms: 17
- max_ms: 17
- error_pct: 0.000%
- jmeter_elapsed_ms: 7207
- drain_ms: 76
- poll_interval_ms: 50
- java_home: /home/sd101t/.jdks/dragonwell-ex-1.8.0_472
- maven_cmd: /opt/idea/plugins/maven/lib/maven3/bin/mvn
- mysql_orders: 100
- mysql_stock: 0
- duplicate_orders: 0
- redis_stock: 0
- redis_order_count: 100
- stream_len: 101
- stream_pending: 0
- correctness: pass
- metrics_csv: docs/archive/redis-stream/JmeterTestSummary/seckill-baseline-lua-stream/metrics.csv
- jtl_file: benchmark/2026-05-16-seckill-baseline-lua-stream-100t-1l-r1.jtl
- summary_csv: docs/archive/redis-stream/JmeterTestSummary/seckill-baseline-lua-stream/2026-05-16-seckill-baseline-lua-stream-100t-1l-summary-r1.csv
- aggregate_csv: docs/archive/redis-stream/JmeterTestSummary/seckill-baseline-lua-stream/2026-05-16-seckill-baseline-lua-stream-100t-1l-aggregate-r1.csv
- html_report: benchmark/report-2026-05-16-seckill-baseline-lua-stream-100t-1l-r1

## Markdown Row

| 2026-05-16 | baseline-lua-stream | seckill-baseline-lua-stream | 100 线程 / 1 次循环 | 100 | 100 | 20.49180 | 2 / 17 | 76 | 100 / 100 | 0 | pass | [run-summary](2026-05-16-seckill-baseline-lua-stream-100t-1l-r1-run-summary.md) |

# Seckill Benchmark Run Summary

- run_id: 2026-05-16-seckill-baseline-lua-stream-1000t-1l-r1
- date: 2026-05-16
- scenario: seckill-baseline-lua-stream
- implementation: baseline-lua-stream
- voucher_id: 11
- stock: 1000
- expected_orders: 1000
- threads: 1000
- loops: 1
- ramp_up_seconds: 5
- total_requests: 1000
- samples: 1000
- throughput: 205.88841
- avg_ms: 2
- median_ms: 1
- p90_ms: 2
- p95_ms: 2
- p99_ms: 22
- max_ms: 27
- error_pct: 0.000%
- jmeter_elapsed_ms: 7193
- drain_ms: 81
- poll_interval_ms: 50
- java_home: /home/sd101t/.jdks/dragonwell-ex-1.8.0_472
- maven_cmd: /opt/idea/plugins/maven/lib/maven3/bin/mvn
- mysql_orders: 1000
- mysql_stock: 0
- duplicate_orders: 0
- redis_stock: 0
- redis_order_count: 1000
- stream_len: 1001
- stream_pending: 0
- correctness: pass
- metrics_csv: docs/JmeterTestSummary/seckill-baseline-lua-stream/metrics.csv
- jtl_file: benchmark/2026-05-16-seckill-baseline-lua-stream-1000t-1l-r1.jtl
- summary_csv: docs/JmeterTestSummary/seckill-baseline-lua-stream/2026-05-16-seckill-baseline-lua-stream-1000t-1l-summary-r1.csv
- aggregate_csv: docs/JmeterTestSummary/seckill-baseline-lua-stream/2026-05-16-seckill-baseline-lua-stream-1000t-1l-aggregate-r1.csv
- html_report: benchmark/report-2026-05-16-seckill-baseline-lua-stream-1000t-1l-r1

## Markdown Row

| 2026-05-16 | baseline-lua-stream | seckill-baseline-lua-stream | 1000 线程 / 1 次循环 | 1000 | 1000 | 205.88841 | 2 / 22 | 81 | 1000 / 1000 | 0 | pass | [run-summary](2026-05-16-seckill-baseline-lua-stream-1000t-1l-r1-run-summary.md) |

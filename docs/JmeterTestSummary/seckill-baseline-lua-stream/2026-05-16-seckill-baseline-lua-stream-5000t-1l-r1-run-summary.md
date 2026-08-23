# Seckill Benchmark Run Summary

- run_id: 2026-05-16-seckill-baseline-lua-stream-5000t-1l-r1
- date: 2026-05-16
- scenario: seckill-baseline-lua-stream
- implementation: baseline-lua-stream
- voucher_id: 11
- stock: 1000
- expected_orders: 1000
- threads: 5000
- loops: 1
- ramp_up_seconds: 5
- total_requests: 5000
- samples: 5000
- throughput: 1153.40254
- avg_ms: 67
- median_ms: 1
- p90_ms: 120
- p95_ms: 155
- p99_ms: 1100
- max_ms: 1116
- error_pct: 0.000%
- jmeter_elapsed_ms: 8312
- drain_ms: 78
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
- jtl_file: benchmark/2026-05-16-seckill-baseline-lua-stream-5000t-1l-r1.jtl
- summary_csv: docs/JmeterTestSummary/seckill-baseline-lua-stream/2026-05-16-seckill-baseline-lua-stream-5000t-1l-summary-r1.csv
- aggregate_csv: docs/JmeterTestSummary/seckill-baseline-lua-stream/2026-05-16-seckill-baseline-lua-stream-5000t-1l-aggregate-r1.csv
- html_report: benchmark/report-2026-05-16-seckill-baseline-lua-stream-5000t-1l-r1

## Markdown Row

| 2026-05-16 | baseline-lua-stream | seckill-baseline-lua-stream | 5000 线程 / 1 次循环 | 1000 | 5000 | 1153.40254 | 155 / 1100 | 78 | 1000 / 1000 | 0 | pass | [run-summary](2026-05-16-seckill-baseline-lua-stream-5000t-1l-r1-run-summary.md) |

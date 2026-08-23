# Seckill Benchmark Run Summary

- run_id: 2026-05-20-seckill-baseline-lua-stream-5000t-1l-r1
- date: 2026-05-20
- scenario: seckill-baseline-lua-stream
- implementation: baseline-lua-stream
- voucher_id: 11
- stream_key: stream.orders
- stream_group: g1
- dead_letter_key: stream.orders.dlq
- stock: 1000
- expected_orders: 1000
- threads: 5000
- loops: 1
- ramp_up_seconds: 5
- total_requests: 5000
- samples: 5000
- throughput: 1055.07491
- avg_ms: 16
- median_ms: 1
- p90_ms: 8
- p95_ms: 107
- p99_ms: 151
- max_ms: 1067
- error_pct: 0.000%
- jmeter_elapsed_ms: 7281
- drain_ms: 71
- poll_interval_ms: 50
- java_home: /home/sd101t/.jdks/dragonwell-ex-1.8.0_472
- maven_cmd: /opt/idea/plugins/maven/lib/maven3/bin/mvn
- project_dir: /home/sd101t/IdeaProjects/hm-dianping-baseline
- output_root: /home/sd101t/IdeaProjects/hm-dianping
- mysql_container: hmdp-mysql
- mysql_database: hmdp
- redis_container: hmdp-redis
- mysql_orders: 1000
- mysql_stock: 0
- duplicate_orders: 0
- redis_stock: 0
- redis_order_count: 1000
- stream_len: 1001
- stream_pending: 0
- stream_dead_letters: 0
- correctness: pass
- metrics_csv: docs/JmeterTestSummary/seckill-baseline-lua-stream/metrics.csv
- jtl_file: benchmark/2026-05-20-seckill-baseline-lua-stream-5000t-1l-r1.jtl
- summary_csv: docs/JmeterTestSummary/seckill-baseline-lua-stream/2026-05-20-seckill-baseline-lua-stream-5000t-1l-summary-r1.csv
- aggregate_csv: docs/JmeterTestSummary/seckill-baseline-lua-stream/2026-05-20-seckill-baseline-lua-stream-5000t-1l-aggregate-r1.csv
- html_report: skipped

## Markdown Row

| 2026-05-20 | baseline-lua-stream | seckill-baseline-lua-stream | 5000 线程 / 1 次循环 | 1000 | 5000 | 1055.07491 | 107 / 151 | 71 | 1000 / 1000 | 0 | 0 | pass | [run-summary](2026-05-20-seckill-baseline-lua-stream-5000t-1l-r1-run-summary.md) |

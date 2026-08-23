# Seckill Benchmark Run Summary

- run_id: 2026-05-20-seckill-baseline-lua-stream-1000t-1l-r2
- date: 2026-05-20
- scenario: seckill-baseline-lua-stream
- implementation: baseline-lua-stream
- voucher_id: 11
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
- throughput: 209.99580
- avg_ms: 1
- median_ms: 1
- p90_ms: 2
- p95_ms: 2
- p99_ms: 2
- max_ms: 21
- error_pct: 0.200%
- jmeter_elapsed_ms: 7287
- drain_ms: 30040
- poll_interval_ms: 50
- java_home: /home/sd101t/.jdks/dragonwell-ex-1.8.0_472
- maven_cmd: /opt/idea/plugins/maven/lib/maven3/bin/mvn
- project_dir: /home/sd101t/IdeaProjects/hm-dianping-baseline
- output_root: /home/sd101t/IdeaProjects/hm-dianping
- mysql_container: hmdp-mysql
- mysql_database: hmdp
- redis_container: hmdp-redis
- mysql_orders: 998
- mysql_stock: 2
- duplicate_orders: 0
- redis_stock: 2
- redis_order_count: 998
- stream_len: 999
- stream_pending: 0
- stream_dead_letters: 0
- correctness: fail
- metrics_csv: docs/JmeterTestSummary/seckill-baseline-lua-stream/metrics.csv
- jtl_file: benchmark/2026-05-20-seckill-baseline-lua-stream-1000t-1l-r2.jtl
- summary_csv: docs/JmeterTestSummary/seckill-baseline-lua-stream/2026-05-20-seckill-baseline-lua-stream-1000t-1l-summary-r2.csv
- aggregate_csv: docs/JmeterTestSummary/seckill-baseline-lua-stream/2026-05-20-seckill-baseline-lua-stream-1000t-1l-aggregate-r2.csv
- html_report: skipped

## Markdown Row

| 2026-05-20 | baseline-lua-stream | seckill-baseline-lua-stream | 1000 线程 / 1 次循环 | 1000 | 1000 | 209.99580 | 2 / 2 | 30040 | 998 / 1000 | 0 | 0 | fail | [run-summary](2026-05-20-seckill-baseline-lua-stream-1000t-1l-r2-run-summary.md) |

-- M8: the same column on ds_1's four tables.
--
-- It lives here and not in db/migration because the two databases are migrated by two separate
-- Flyway instances (see ShardingDataSourceConfiguration): a column added only on one side would
-- work for every buyer with an even user id and fail for every buyer with an odd one.
--
-- Reasoning for the type and the nullability is in db/migration/V17__trade_order_trace_id.sql.

ALTER TABLE `trade_order_0` ADD COLUMN `trace_id` varchar(64)
  CHARACTER SET ascii COLLATE ascii_bin NULL DEFAULT NULL
  COMMENT '创建该订单的请求 traceId' AFTER `version`;
ALTER TABLE `trade_order_1` ADD COLUMN `trace_id` varchar(64)
  CHARACTER SET ascii COLLATE ascii_bin NULL DEFAULT NULL
  COMMENT '创建该订单的请求 traceId' AFTER `version`;
ALTER TABLE `trade_order_2` ADD COLUMN `trace_id` varchar(64)
  CHARACTER SET ascii COLLATE ascii_bin NULL DEFAULT NULL
  COMMENT '创建该订单的请求 traceId' AFTER `version`;
ALTER TABLE `trade_order_3` ADD COLUMN `trace_id` varchar(64)
  CHARACTER SET ascii COLLATE ascii_bin NULL DEFAULT NULL
  COMMENT '创建该订单的请求 traceId' AFTER `version`;

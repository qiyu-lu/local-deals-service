-- M8: the request that created the order, kept on the row.
--
-- A consume batch merges many buyers into one INSERT, so the statement has no single trace and
-- a SQL comment could not carry one. The column can: it survives the log rotation, and it is
-- what makes "which of the three instances admitted this buyer, and which one persisted the
-- order" answerable from the data rather than from a guess.
--
-- Nullable on purpose. An order rebuilt by the reconciler from a Redis reservation has no
-- request behind it any more, and orders written before this migration never had one.
--
-- 32 hex characters is nginx's $request_id; 64 is TraceContext's ceiling. ascii_bin because the
-- value is an opaque token, never compared case-insensitively and never collated.

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

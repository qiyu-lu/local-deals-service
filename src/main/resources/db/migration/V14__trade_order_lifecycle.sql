-- M2: order lifecycle and coupon assets.
--
-- trade_order replaces tb_voucher_order. An order is created PENDING_PAY by the seckill consumer
-- and moves only through OrderStateMachine (conditional UPDATE on status). user_coupon is the
-- single coupon asset for both purchases (issued on payment) and marketing grants.

ALTER TABLE `tb_voucher`
  ADD COLUMN `valid_days` int(10) UNSIGNED NOT NULL DEFAULT 30
    COMMENT '券资产有效天数，从发券时刻起算' AFTER `actual_value`;

CREATE TABLE `trade_order` (
  `order_no` bigint(20) UNSIGNED NOT NULL COMMENT '订单号，即秒杀准入时的 orderId',
  `user_id` bigint(20) UNSIGNED NOT NULL,
  `voucher_id` bigint(20) UNSIGNED NOT NULL,
  `shop_id` bigint(20) UNSIGNED NOT NULL COMMENT '下单时快照',
  `merchant_id` bigint(20) UNSIGNED NOT NULL COMMENT '下单时快照，商户侧查询与核销的数据范围',
  `amount` bigint(20) UNSIGNED NOT NULL COMMENT '应付金额（分），下单时快照 tb_voucher.pay_value',
  `status` enum('PENDING_PAY','PAID','USED','CLOSED','REFUNDING','REFUNDED')
    CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `expire_at` datetime(3) NOT NULL COMMENT '支付截止时间，按数据库时钟计算',
  `paid_at` datetime(3) NULL DEFAULT NULL,
  `closed_at` datetime(3) NULL DEFAULT NULL,
  `used_at` datetime(3) NULL DEFAULT NULL,
  `refunded_at` datetime(3) NULL DEFAULT NULL,
  `release_pending` tinyint(3) UNSIGNED NULL DEFAULT NULL
    COMMENT '1=DB 库存已回补、Redis 预占待释放；NULL=无待办',
  `version` int(10) UNSIGNED NOT NULL DEFAULT 0 COMMENT '每次状态流转 +1',
  -- Unique keys allow any number of NULLs: CLOSED/REFUNDED orders drop out of the purchase limit.
  `active_flag` tinyint(3) UNSIGNED
    GENERATED ALWAYS AS (IF(`status` IN ('CLOSED', 'REFUNDED'), NULL, 1)) STORED,
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`order_no`),
  UNIQUE KEY `uk_trade_order_user_voucher_active` (`user_id`, `voucher_id`, `active_flag`),
  KEY `idx_trade_order_status_expire` (`status`, `expire_at`),
  KEY `idx_trade_order_user_time` (`user_id`, `create_time`),
  KEY `idx_trade_order_merchant_time` (`merchant_id`, `create_time`),
  KEY `idx_trade_order_release_pending` (`release_pending`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='交易订单';

CREATE TABLE `order_state_log` (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT,
  `order_no` bigint(20) UNSIGNED NOT NULL,
  `from_status` enum('PENDING_PAY','PAID','USED','CLOSED','REFUNDING','REFUNDED')
    CHARACTER SET ascii COLLATE ascii_bin NULL DEFAULT NULL COMMENT '创建时为空',
  `to_status` enum('PENDING_PAY','PAID','USED','CLOSED','REFUNDING','REFUNDED')
    CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `event` varchar(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `operator` varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
    COMMENT 'SYSTEM / CHANNEL / USER:<id> / ADMIN:<id>',
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_order_state_log_order` (`order_no`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单状态流转审计';

CREATE TABLE `payment_record` (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT,
  `pay_no` varchar(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '我方支付单号',
  `order_no` bigint(20) UNSIGNED NOT NULL,
  `user_id` bigint(20) UNSIGNED NOT NULL,
  `amount` bigint(20) UNSIGNED NOT NULL COMMENT '应付金额（分）',
  `channel` varchar(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `status` enum('WAITING','SUCCESS','ABNORMAL') CHARACTER SET ascii COLLATE ascii_bin
    NOT NULL DEFAULT 'WAITING',
  `channel_txn_no` varchar(64) CHARACTER SET ascii COLLATE ascii_bin NULL DEFAULT NULL
    COMMENT '渠道流水号，回调幂等键',
  `paid_amount` bigint(20) UNSIGNED NULL DEFAULT NULL COMMENT '渠道回调金额（分）',
  `paid_at` datetime(3) NULL DEFAULT NULL,
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_payment_record_pay_no` (`pay_no`),
  UNIQUE KEY `uk_payment_record_channel_txn_no` (`channel_txn_no`),
  KEY `idx_payment_record_order` (`order_no`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='支付流水';

CREATE TABLE `refund_record` (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT,
  `refund_no` varchar(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
    COMMENT 'USER: RU<order_no>，AUTO: RA<pay_no>；确定性单号即幂等键',
  `order_no` bigint(20) UNSIGNED NOT NULL,
  `pay_no` varchar(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `user_id` bigint(20) UNSIGNED NOT NULL,
  `amount` bigint(20) UNSIGNED NOT NULL,
  `type` enum('USER','AUTO') CHARACTER SET ascii COLLATE ascii_bin NOT NULL
    COMMENT 'USER=用户申请；AUTO=关单后迟到的支付、重复支付等自动退款',
  `reason` varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `status` enum('PENDING','SUCCESS') CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PENDING',
  `channel_refund_no` varchar(64) CHARACTER SET ascii COLLATE ascii_bin NULL DEFAULT NULL,
  `request_attempts` int(10) UNSIGNED NOT NULL DEFAULT 0,
  `finished_at` datetime(3) NULL DEFAULT NULL,
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_refund_record_refund_no` (`refund_no`),
  UNIQUE KEY `uk_refund_record_channel_refund_no` (`channel_refund_no`),
  KEY `idx_refund_record_order` (`order_no`, `id`),
  KEY `idx_refund_record_status_time` (`status`, `update_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='退款流水';

CREATE TABLE `user_coupon` (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT,
  `coupon_no` varchar(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
    COMMENT 'P<order_no> / G<grant_id>；一个来源最多一张券',
  `user_id` bigint(20) UNSIGNED NOT NULL,
  `voucher_id` bigint(20) UNSIGNED NOT NULL,
  `merchant_id` bigint(20) UNSIGNED NOT NULL,
  `shop_id` bigint(20) UNSIGNED NOT NULL,
  `source` enum('PURCHASE','CLAIM','ADMIN_GRANT','TASK_REWARD','BATCH_GRANT')
    CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `source_ref` varchar(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '订单号或 grant id',
  `status` enum('AVAILABLE','USED','EXPIRED','REFUNDED','FROZEN') CHARACTER SET ascii COLLATE ascii_bin
    NOT NULL DEFAULT 'AVAILABLE',
  `valid_from` datetime(3) NOT NULL,
  `valid_to` datetime(3) NOT NULL,
  `verify_code` char(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '到店核销码，80 bit 随机',
  `used_at` datetime(3) NULL DEFAULT NULL,
  `verified_by` bigint(20) UNSIGNED NULL DEFAULT NULL COMMENT '核销的后台账号',
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_coupon_coupon_no` (`coupon_no`),
  UNIQUE KEY `uk_user_coupon_verify_code` (`verify_code`),
  KEY `idx_user_coupon_user_status` (`user_id`, `status`, `id`),
  KEY `idx_user_coupon_status_valid_to` (`status`, `valid_to`),
  KEY `idx_user_coupon_merchant_status` (`merchant_id`, `status`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户券资产';

-- V1 orders were final at creation: there was no payment step. Carry them over as PAID with a
-- matching coupon, so nothing is lost and "PAID implies one coupon" holds from the first row.
INSERT INTO `trade_order` (`order_no`, `user_id`, `voucher_id`, `shop_id`, `merchant_id`, `amount`,
                           `status`, `expire_at`, `paid_at`, `create_time`)
SELECT o.`id`, o.`user_id`, o.`voucher_id`, COALESCE(v.`shop_id`, 0), COALESCE(s.`merchant_id`, 0),
       COALESCE(v.`pay_value`, 0), 'PAID', o.`create_time`, o.`create_time`, o.`create_time`
FROM `tb_voucher_order` o
LEFT JOIN `tb_voucher` v ON v.`id` = o.`voucher_id`
LEFT JOIN `tb_shop` s ON s.`id` = v.`shop_id`;

INSERT INTO `order_state_log` (`order_no`, `from_status`, `to_status`, `event`, `operator`)
SELECT `order_no`, NULL, 'PAID', 'MIGRATED_FROM_V1', 'SYSTEM' FROM `trade_order`;

INSERT INTO `user_coupon` (`coupon_no`, `user_id`, `voucher_id`, `merchant_id`, `shop_id`, `source`,
                           `source_ref`, `status`, `valid_from`, `valid_to`, `verify_code`)
SELECT CONCAT('P', t.`order_no`), t.`user_id`, t.`voucher_id`, t.`merchant_id`, t.`shop_id`, 'PURCHASE',
       t.`order_no`, 'AVAILABLE', t.`paid_at`,
       DATE_ADD(t.`paid_at`, INTERVAL COALESCE(v.`valid_days`, 30) DAY),
       UPPER(LEFT(SHA2(CONCAT(t.`order_no`, ':', HEX(RANDOM_BYTES(16))), 256), 16))
FROM `trade_order` t
LEFT JOIN `tb_voucher` v ON v.`id` = t.`voucher_id`;

DROP TABLE `tb_voucher_order`;

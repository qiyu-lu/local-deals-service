-- M6: the second order database. ds_1 holds the odd slots; its table k holds slot 2k+1.
--
-- Only the sharded tables live here. Vouchers, shops, users, marketing and RBAC stay whole in
-- ds_0 as ShardingSphere single tables, because a stock row that existed in two databases would
-- need a distributed write to stay one number.
--
-- The carry-over reads ${legacySchema}, the schema ds_0 uses, which assumes the two databases
-- are schemas of one server — true for this project's local and benchmark topology. Pointed at a
-- separate server, this migration fails loudly rather than silently losing the odd slots.

CREATE TABLE `trade_order_0` (
  `order_no` bigint(20) UNSIGNED NOT NULL COMMENT '订单号，即秒杀准入时的 orderId',
  `user_id` bigint(20) UNSIGNED NOT NULL COMMENT '分片键：低位基因与 order_no 一致',
  `voucher_id` bigint(20) UNSIGNED NOT NULL,
  `shop_id` bigint(20) UNSIGNED NOT NULL COMMENT '下单时快照',
  `merchant_id` bigint(20) UNSIGNED NOT NULL COMMENT '下单时快照',
  `amount` bigint(20) UNSIGNED NOT NULL COMMENT '应付金额（分）',
  `status` enum('PENDING_PAY','PAID','USED','CLOSED','REFUNDING','REFUNDED')
    CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `expire_at` datetime(3) NOT NULL COMMENT '支付截止时间，按数据库时钟计算',
  `paid_at` datetime(3) NULL DEFAULT NULL,
  `closed_at` datetime(3) NULL DEFAULT NULL,
  `used_at` datetime(3) NULL DEFAULT NULL,
  `refunded_at` datetime(3) NULL DEFAULT NULL,
  `release_pending` tinyint(3) UNSIGNED NULL DEFAULT NULL
    COMMENT '1=DB 库存已回补、Redis 预占待释放；NULL=无待办',
  `version` int(10) UNSIGNED NOT NULL DEFAULT 0,
  `active_flag` tinyint(3) UNSIGNED
    GENERATED ALWAYS AS (IF(`status` IN ('CLOSED', 'REFUNDED'), NULL, 1)) STORED,
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`order_no`),
  -- A user's rows all live in this one table, so uniqueness here is uniqueness everywhere.
  UNIQUE KEY `uk_trade_order_user_voucher_active` (`user_id`, `voucher_id`, `active_flag`),
  KEY `idx_trade_order_status_expire` (`status`, `expire_at`),
  KEY `idx_trade_order_user_time` (`user_id`, `create_time`),
  KEY `idx_trade_order_merchant_time` (`merchant_id`, `create_time`),
  KEY `idx_trade_order_release_pending` (`release_pending`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='交易订单 分片 0';

CREATE TABLE `trade_order_1` (
  `order_no` bigint(20) UNSIGNED NOT NULL COMMENT '订单号，即秒杀准入时的 orderId',
  `user_id` bigint(20) UNSIGNED NOT NULL COMMENT '分片键：低位基因与 order_no 一致',
  `voucher_id` bigint(20) UNSIGNED NOT NULL,
  `shop_id` bigint(20) UNSIGNED NOT NULL COMMENT '下单时快照',
  `merchant_id` bigint(20) UNSIGNED NOT NULL COMMENT '下单时快照',
  `amount` bigint(20) UNSIGNED NOT NULL COMMENT '应付金额（分）',
  `status` enum('PENDING_PAY','PAID','USED','CLOSED','REFUNDING','REFUNDED')
    CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `expire_at` datetime(3) NOT NULL COMMENT '支付截止时间，按数据库时钟计算',
  `paid_at` datetime(3) NULL DEFAULT NULL,
  `closed_at` datetime(3) NULL DEFAULT NULL,
  `used_at` datetime(3) NULL DEFAULT NULL,
  `refunded_at` datetime(3) NULL DEFAULT NULL,
  `release_pending` tinyint(3) UNSIGNED NULL DEFAULT NULL
    COMMENT '1=DB 库存已回补、Redis 预占待释放；NULL=无待办',
  `version` int(10) UNSIGNED NOT NULL DEFAULT 0,
  `active_flag` tinyint(3) UNSIGNED
    GENERATED ALWAYS AS (IF(`status` IN ('CLOSED', 'REFUNDED'), NULL, 1)) STORED,
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`order_no`),
  -- A user's rows all live in this one table, so uniqueness here is uniqueness everywhere.
  UNIQUE KEY `uk_trade_order_user_voucher_active` (`user_id`, `voucher_id`, `active_flag`),
  KEY `idx_trade_order_status_expire` (`status`, `expire_at`),
  KEY `idx_trade_order_user_time` (`user_id`, `create_time`),
  KEY `idx_trade_order_merchant_time` (`merchant_id`, `create_time`),
  KEY `idx_trade_order_release_pending` (`release_pending`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='交易订单 分片 1';

CREATE TABLE `trade_order_2` (
  `order_no` bigint(20) UNSIGNED NOT NULL COMMENT '订单号，即秒杀准入时的 orderId',
  `user_id` bigint(20) UNSIGNED NOT NULL COMMENT '分片键：低位基因与 order_no 一致',
  `voucher_id` bigint(20) UNSIGNED NOT NULL,
  `shop_id` bigint(20) UNSIGNED NOT NULL COMMENT '下单时快照',
  `merchant_id` bigint(20) UNSIGNED NOT NULL COMMENT '下单时快照',
  `amount` bigint(20) UNSIGNED NOT NULL COMMENT '应付金额（分）',
  `status` enum('PENDING_PAY','PAID','USED','CLOSED','REFUNDING','REFUNDED')
    CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `expire_at` datetime(3) NOT NULL COMMENT '支付截止时间，按数据库时钟计算',
  `paid_at` datetime(3) NULL DEFAULT NULL,
  `closed_at` datetime(3) NULL DEFAULT NULL,
  `used_at` datetime(3) NULL DEFAULT NULL,
  `refunded_at` datetime(3) NULL DEFAULT NULL,
  `release_pending` tinyint(3) UNSIGNED NULL DEFAULT NULL
    COMMENT '1=DB 库存已回补、Redis 预占待释放；NULL=无待办',
  `version` int(10) UNSIGNED NOT NULL DEFAULT 0,
  `active_flag` tinyint(3) UNSIGNED
    GENERATED ALWAYS AS (IF(`status` IN ('CLOSED', 'REFUNDED'), NULL, 1)) STORED,
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`order_no`),
  -- A user's rows all live in this one table, so uniqueness here is uniqueness everywhere.
  UNIQUE KEY `uk_trade_order_user_voucher_active` (`user_id`, `voucher_id`, `active_flag`),
  KEY `idx_trade_order_status_expire` (`status`, `expire_at`),
  KEY `idx_trade_order_user_time` (`user_id`, `create_time`),
  KEY `idx_trade_order_merchant_time` (`merchant_id`, `create_time`),
  KEY `idx_trade_order_release_pending` (`release_pending`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='交易订单 分片 2';

CREATE TABLE `trade_order_3` (
  `order_no` bigint(20) UNSIGNED NOT NULL COMMENT '订单号，即秒杀准入时的 orderId',
  `user_id` bigint(20) UNSIGNED NOT NULL COMMENT '分片键：低位基因与 order_no 一致',
  `voucher_id` bigint(20) UNSIGNED NOT NULL,
  `shop_id` bigint(20) UNSIGNED NOT NULL COMMENT '下单时快照',
  `merchant_id` bigint(20) UNSIGNED NOT NULL COMMENT '下单时快照',
  `amount` bigint(20) UNSIGNED NOT NULL COMMENT '应付金额（分）',
  `status` enum('PENDING_PAY','PAID','USED','CLOSED','REFUNDING','REFUNDED')
    CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `expire_at` datetime(3) NOT NULL COMMENT '支付截止时间，按数据库时钟计算',
  `paid_at` datetime(3) NULL DEFAULT NULL,
  `closed_at` datetime(3) NULL DEFAULT NULL,
  `used_at` datetime(3) NULL DEFAULT NULL,
  `refunded_at` datetime(3) NULL DEFAULT NULL,
  `release_pending` tinyint(3) UNSIGNED NULL DEFAULT NULL
    COMMENT '1=DB 库存已回补、Redis 预占待释放；NULL=无待办',
  `version` int(10) UNSIGNED NOT NULL DEFAULT 0,
  `active_flag` tinyint(3) UNSIGNED
    GENERATED ALWAYS AS (IF(`status` IN ('CLOSED', 'REFUNDED'), NULL, 1)) STORED,
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`order_no`),
  -- A user's rows all live in this one table, so uniqueness here is uniqueness everywhere.
  UNIQUE KEY `uk_trade_order_user_voucher_active` (`user_id`, `voucher_id`, `active_flag`),
  KEY `idx_trade_order_status_expire` (`status`, `expire_at`),
  KEY `idx_trade_order_user_time` (`user_id`, `create_time`),
  KEY `idx_trade_order_merchant_time` (`merchant_id`, `create_time`),
  KEY `idx_trade_order_release_pending` (`release_pending`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='交易订单 分片 3';

CREATE TABLE `order_state_log_0` (
  `id` bigint(20) UNSIGNED NOT NULL COMMENT 'ShardingSphere SNOWFLAKE；分片后自增会在每张表里重复',
  `order_no` bigint(20) UNSIGNED NOT NULL COMMENT '分片键',
  `from_status` enum('PENDING_PAY','PAID','USED','CLOSED','REFUNDING','REFUNDED')
    CHARACTER SET ascii COLLATE ascii_bin NULL DEFAULT NULL COMMENT '创建时为空',
  `to_status` enum('PENDING_PAY','PAID','USED','CLOSED','REFUNDING','REFUNDED')
    CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `event` varchar(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `operator` varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_order_state_log_order` (`order_no`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单状态流转审计 分片 0';

CREATE TABLE `order_state_log_1` (
  `id` bigint(20) UNSIGNED NOT NULL COMMENT 'ShardingSphere SNOWFLAKE；分片后自增会在每张表里重复',
  `order_no` bigint(20) UNSIGNED NOT NULL COMMENT '分片键',
  `from_status` enum('PENDING_PAY','PAID','USED','CLOSED','REFUNDING','REFUNDED')
    CHARACTER SET ascii COLLATE ascii_bin NULL DEFAULT NULL COMMENT '创建时为空',
  `to_status` enum('PENDING_PAY','PAID','USED','CLOSED','REFUNDING','REFUNDED')
    CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `event` varchar(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `operator` varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_order_state_log_order` (`order_no`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单状态流转审计 分片 1';

CREATE TABLE `order_state_log_2` (
  `id` bigint(20) UNSIGNED NOT NULL COMMENT 'ShardingSphere SNOWFLAKE；分片后自增会在每张表里重复',
  `order_no` bigint(20) UNSIGNED NOT NULL COMMENT '分片键',
  `from_status` enum('PENDING_PAY','PAID','USED','CLOSED','REFUNDING','REFUNDED')
    CHARACTER SET ascii COLLATE ascii_bin NULL DEFAULT NULL COMMENT '创建时为空',
  `to_status` enum('PENDING_PAY','PAID','USED','CLOSED','REFUNDING','REFUNDED')
    CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `event` varchar(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `operator` varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_order_state_log_order` (`order_no`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单状态流转审计 分片 2';

CREATE TABLE `order_state_log_3` (
  `id` bigint(20) UNSIGNED NOT NULL COMMENT 'ShardingSphere SNOWFLAKE；分片后自增会在每张表里重复',
  `order_no` bigint(20) UNSIGNED NOT NULL COMMENT '分片键',
  `from_status` enum('PENDING_PAY','PAID','USED','CLOSED','REFUNDING','REFUNDED')
    CHARACTER SET ascii COLLATE ascii_bin NULL DEFAULT NULL COMMENT '创建时为空',
  `to_status` enum('PENDING_PAY','PAID','USED','CLOSED','REFUNDING','REFUNDED')
    CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `event` varchar(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `operator` varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_order_state_log_order` (`order_no`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单状态流转审计 分片 3';

CREATE TABLE `payment_record_0` (
  `id` bigint(20) UNSIGNED NOT NULL COMMENT 'ShardingSphere SNOWFLAKE',
  `pay_no` varchar(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '<order_no>-<n>，含分片基因',
  `order_no` bigint(20) UNSIGNED NOT NULL COMMENT '分片键',
  `user_id` bigint(20) UNSIGNED NOT NULL,
  `amount` bigint(20) UNSIGNED NOT NULL COMMENT '应付金额（分）',
  `channel` varchar(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `status` enum('WAITING','SUCCESS','ABNORMAL') CHARACTER SET ascii COLLATE ascii_bin
    NOT NULL DEFAULT 'WAITING',
  `channel_txn_no` varchar(64) CHARACTER SET ascii COLLATE ascii_bin NULL DEFAULT NULL
    COMMENT '渠道流水号；分片后只在本片唯一，回调总是带 pay_no，幂等不受影响',
  `paid_amount` bigint(20) UNSIGNED NULL DEFAULT NULL,
  `paid_at` datetime(3) NULL DEFAULT NULL,
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_payment_record_pay_no` (`pay_no`),
  UNIQUE KEY `uk_payment_record_channel_txn_no` (`channel_txn_no`),
  KEY `idx_payment_record_order` (`order_no`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='支付流水 分片 0';

CREATE TABLE `payment_record_1` (
  `id` bigint(20) UNSIGNED NOT NULL COMMENT 'ShardingSphere SNOWFLAKE',
  `pay_no` varchar(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '<order_no>-<n>，含分片基因',
  `order_no` bigint(20) UNSIGNED NOT NULL COMMENT '分片键',
  `user_id` bigint(20) UNSIGNED NOT NULL,
  `amount` bigint(20) UNSIGNED NOT NULL COMMENT '应付金额（分）',
  `channel` varchar(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `status` enum('WAITING','SUCCESS','ABNORMAL') CHARACTER SET ascii COLLATE ascii_bin
    NOT NULL DEFAULT 'WAITING',
  `channel_txn_no` varchar(64) CHARACTER SET ascii COLLATE ascii_bin NULL DEFAULT NULL
    COMMENT '渠道流水号；分片后只在本片唯一，回调总是带 pay_no，幂等不受影响',
  `paid_amount` bigint(20) UNSIGNED NULL DEFAULT NULL,
  `paid_at` datetime(3) NULL DEFAULT NULL,
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_payment_record_pay_no` (`pay_no`),
  UNIQUE KEY `uk_payment_record_channel_txn_no` (`channel_txn_no`),
  KEY `idx_payment_record_order` (`order_no`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='支付流水 分片 1';

CREATE TABLE `payment_record_2` (
  `id` bigint(20) UNSIGNED NOT NULL COMMENT 'ShardingSphere SNOWFLAKE',
  `pay_no` varchar(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '<order_no>-<n>，含分片基因',
  `order_no` bigint(20) UNSIGNED NOT NULL COMMENT '分片键',
  `user_id` bigint(20) UNSIGNED NOT NULL,
  `amount` bigint(20) UNSIGNED NOT NULL COMMENT '应付金额（分）',
  `channel` varchar(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `status` enum('WAITING','SUCCESS','ABNORMAL') CHARACTER SET ascii COLLATE ascii_bin
    NOT NULL DEFAULT 'WAITING',
  `channel_txn_no` varchar(64) CHARACTER SET ascii COLLATE ascii_bin NULL DEFAULT NULL
    COMMENT '渠道流水号；分片后只在本片唯一，回调总是带 pay_no，幂等不受影响',
  `paid_amount` bigint(20) UNSIGNED NULL DEFAULT NULL,
  `paid_at` datetime(3) NULL DEFAULT NULL,
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_payment_record_pay_no` (`pay_no`),
  UNIQUE KEY `uk_payment_record_channel_txn_no` (`channel_txn_no`),
  KEY `idx_payment_record_order` (`order_no`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='支付流水 分片 2';

CREATE TABLE `payment_record_3` (
  `id` bigint(20) UNSIGNED NOT NULL COMMENT 'ShardingSphere SNOWFLAKE',
  `pay_no` varchar(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '<order_no>-<n>，含分片基因',
  `order_no` bigint(20) UNSIGNED NOT NULL COMMENT '分片键',
  `user_id` bigint(20) UNSIGNED NOT NULL,
  `amount` bigint(20) UNSIGNED NOT NULL COMMENT '应付金额（分）',
  `channel` varchar(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `status` enum('WAITING','SUCCESS','ABNORMAL') CHARACTER SET ascii COLLATE ascii_bin
    NOT NULL DEFAULT 'WAITING',
  `channel_txn_no` varchar(64) CHARACTER SET ascii COLLATE ascii_bin NULL DEFAULT NULL
    COMMENT '渠道流水号；分片后只在本片唯一，回调总是带 pay_no，幂等不受影响',
  `paid_amount` bigint(20) UNSIGNED NULL DEFAULT NULL,
  `paid_at` datetime(3) NULL DEFAULT NULL,
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_payment_record_pay_no` (`pay_no`),
  UNIQUE KEY `uk_payment_record_channel_txn_no` (`channel_txn_no`),
  KEY `idx_payment_record_order` (`order_no`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='支付流水 分片 3';

CREATE TABLE `refund_record_0` (
  `id` bigint(20) UNSIGNED NOT NULL COMMENT 'ShardingSphere SNOWFLAKE',
  `refund_no` varchar(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
    COMMENT 'RU<order_no> / RA<pay_no>，两种都含分片基因',
  `order_no` bigint(20) UNSIGNED NOT NULL COMMENT '分片键',
  `pay_no` varchar(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `user_id` bigint(20) UNSIGNED NOT NULL,
  `amount` bigint(20) UNSIGNED NOT NULL,
  `type` enum('USER','AUTO') CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='退款流水 分片 0';

CREATE TABLE `refund_record_1` (
  `id` bigint(20) UNSIGNED NOT NULL COMMENT 'ShardingSphere SNOWFLAKE',
  `refund_no` varchar(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
    COMMENT 'RU<order_no> / RA<pay_no>，两种都含分片基因',
  `order_no` bigint(20) UNSIGNED NOT NULL COMMENT '分片键',
  `pay_no` varchar(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `user_id` bigint(20) UNSIGNED NOT NULL,
  `amount` bigint(20) UNSIGNED NOT NULL,
  `type` enum('USER','AUTO') CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='退款流水 分片 1';

CREATE TABLE `refund_record_2` (
  `id` bigint(20) UNSIGNED NOT NULL COMMENT 'ShardingSphere SNOWFLAKE',
  `refund_no` varchar(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
    COMMENT 'RU<order_no> / RA<pay_no>，两种都含分片基因',
  `order_no` bigint(20) UNSIGNED NOT NULL COMMENT '分片键',
  `pay_no` varchar(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `user_id` bigint(20) UNSIGNED NOT NULL,
  `amount` bigint(20) UNSIGNED NOT NULL,
  `type` enum('USER','AUTO') CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='退款流水 分片 2';

CREATE TABLE `refund_record_3` (
  `id` bigint(20) UNSIGNED NOT NULL COMMENT 'ShardingSphere SNOWFLAKE',
  `refund_no` varchar(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
    COMMENT 'RU<order_no> / RA<pay_no>，两种都含分片基因',
  `order_no` bigint(20) UNSIGNED NOT NULL COMMENT '分片键',
  `pay_no` varchar(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `user_id` bigint(20) UNSIGNED NOT NULL,
  `amount` bigint(20) UNSIGNED NOT NULL,
  `type` enum('USER','AUTO') CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='退款流水 分片 3';

CREATE TABLE `user_coupon_0` (
  `id` bigint(20) UNSIGNED NOT NULL COMMENT 'ShardingSphere SNOWFLAKE',
  `coupon_no` varchar(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
    COMMENT 'P<order_no> 含基因可单片命中；G<grant_id> 不含，按 coupon_no 查会广播',
  `user_id` bigint(20) UNSIGNED NOT NULL COMMENT '分片键',
  `voucher_id` bigint(20) UNSIGNED NOT NULL,
  `merchant_id` bigint(20) UNSIGNED NOT NULL,
  `shop_id` bigint(20) UNSIGNED NOT NULL,
  `source` enum('PURCHASE','CLAIM','ADMIN_GRANT','TASK_REWARD','BATCH_GRANT')
    CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `source_ref` varchar(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `status` enum('AVAILABLE','USED','EXPIRED','REFUNDED','FROZEN') CHARACTER SET ascii COLLATE ascii_bin
    NOT NULL DEFAULT 'AVAILABLE',
  `valid_from` datetime(3) NOT NULL,
  `valid_to` datetime(3) NOT NULL,
  `verify_code` char(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
    COMMENT '80 bit 随机；分片后只在本片唯一，核销按它查是广播',
  `used_at` datetime(3) NULL DEFAULT NULL,
  `verified_by` bigint(20) UNSIGNED NULL DEFAULT NULL,
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_coupon_coupon_no` (`coupon_no`),
  UNIQUE KEY `uk_user_coupon_verify_code` (`verify_code`),
  KEY `idx_user_coupon_user_status` (`user_id`, `status`, `id`),
  KEY `idx_user_coupon_status_valid_to` (`status`, `valid_to`),
  KEY `idx_user_coupon_merchant_status` (`merchant_id`, `status`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户券资产 分片 0';

CREATE TABLE `user_coupon_1` (
  `id` bigint(20) UNSIGNED NOT NULL COMMENT 'ShardingSphere SNOWFLAKE',
  `coupon_no` varchar(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
    COMMENT 'P<order_no> 含基因可单片命中；G<grant_id> 不含，按 coupon_no 查会广播',
  `user_id` bigint(20) UNSIGNED NOT NULL COMMENT '分片键',
  `voucher_id` bigint(20) UNSIGNED NOT NULL,
  `merchant_id` bigint(20) UNSIGNED NOT NULL,
  `shop_id` bigint(20) UNSIGNED NOT NULL,
  `source` enum('PURCHASE','CLAIM','ADMIN_GRANT','TASK_REWARD','BATCH_GRANT')
    CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `source_ref` varchar(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `status` enum('AVAILABLE','USED','EXPIRED','REFUNDED','FROZEN') CHARACTER SET ascii COLLATE ascii_bin
    NOT NULL DEFAULT 'AVAILABLE',
  `valid_from` datetime(3) NOT NULL,
  `valid_to` datetime(3) NOT NULL,
  `verify_code` char(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
    COMMENT '80 bit 随机；分片后只在本片唯一，核销按它查是广播',
  `used_at` datetime(3) NULL DEFAULT NULL,
  `verified_by` bigint(20) UNSIGNED NULL DEFAULT NULL,
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_coupon_coupon_no` (`coupon_no`),
  UNIQUE KEY `uk_user_coupon_verify_code` (`verify_code`),
  KEY `idx_user_coupon_user_status` (`user_id`, `status`, `id`),
  KEY `idx_user_coupon_status_valid_to` (`status`, `valid_to`),
  KEY `idx_user_coupon_merchant_status` (`merchant_id`, `status`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户券资产 分片 1';

CREATE TABLE `user_coupon_2` (
  `id` bigint(20) UNSIGNED NOT NULL COMMENT 'ShardingSphere SNOWFLAKE',
  `coupon_no` varchar(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
    COMMENT 'P<order_no> 含基因可单片命中；G<grant_id> 不含，按 coupon_no 查会广播',
  `user_id` bigint(20) UNSIGNED NOT NULL COMMENT '分片键',
  `voucher_id` bigint(20) UNSIGNED NOT NULL,
  `merchant_id` bigint(20) UNSIGNED NOT NULL,
  `shop_id` bigint(20) UNSIGNED NOT NULL,
  `source` enum('PURCHASE','CLAIM','ADMIN_GRANT','TASK_REWARD','BATCH_GRANT')
    CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `source_ref` varchar(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `status` enum('AVAILABLE','USED','EXPIRED','REFUNDED','FROZEN') CHARACTER SET ascii COLLATE ascii_bin
    NOT NULL DEFAULT 'AVAILABLE',
  `valid_from` datetime(3) NOT NULL,
  `valid_to` datetime(3) NOT NULL,
  `verify_code` char(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
    COMMENT '80 bit 随机；分片后只在本片唯一，核销按它查是广播',
  `used_at` datetime(3) NULL DEFAULT NULL,
  `verified_by` bigint(20) UNSIGNED NULL DEFAULT NULL,
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_coupon_coupon_no` (`coupon_no`),
  UNIQUE KEY `uk_user_coupon_verify_code` (`verify_code`),
  KEY `idx_user_coupon_user_status` (`user_id`, `status`, `id`),
  KEY `idx_user_coupon_status_valid_to` (`status`, `valid_to`),
  KEY `idx_user_coupon_merchant_status` (`merchant_id`, `status`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户券资产 分片 2';

CREATE TABLE `user_coupon_3` (
  `id` bigint(20) UNSIGNED NOT NULL COMMENT 'ShardingSphere SNOWFLAKE',
  `coupon_no` varchar(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
    COMMENT 'P<order_no> 含基因可单片命中；G<grant_id> 不含，按 coupon_no 查会广播',
  `user_id` bigint(20) UNSIGNED NOT NULL COMMENT '分片键',
  `voucher_id` bigint(20) UNSIGNED NOT NULL,
  `merchant_id` bigint(20) UNSIGNED NOT NULL,
  `shop_id` bigint(20) UNSIGNED NOT NULL,
  `source` enum('PURCHASE','CLAIM','ADMIN_GRANT','TASK_REWARD','BATCH_GRANT')
    CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `source_ref` varchar(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `status` enum('AVAILABLE','USED','EXPIRED','REFUNDED','FROZEN') CHARACTER SET ascii COLLATE ascii_bin
    NOT NULL DEFAULT 'AVAILABLE',
  `valid_from` datetime(3) NOT NULL,
  `valid_to` datetime(3) NOT NULL,
  `verify_code` char(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
    COMMENT '80 bit 随机；分片后只在本片唯一，核销按它查是广播',
  `used_at` datetime(3) NULL DEFAULT NULL,
  `verified_by` bigint(20) UNSIGNED NULL DEFAULT NULL,
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `update_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_coupon_coupon_no` (`coupon_no`),
  UNIQUE KEY `uk_user_coupon_verify_code` (`verify_code`),
  KEY `idx_user_coupon_user_status` (`user_id`, `status`, `id`),
  KEY `idx_user_coupon_status_valid_to` (`status`, `valid_to`),
  KEY `idx_user_coupon_merchant_status` (`merchant_id`, `status`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户券资产 分片 3';


INSERT INTO `trade_order_0` (`order_no`, `user_id`, `voucher_id`, `shop_id`, `merchant_id`, `amount`, `status`, `expire_at`, `paid_at`, `closed_at`, `used_at`, `refunded_at`, `release_pending`, `version`, `create_time`, `update_time`)
SELECT `order_no`, `user_id`, `voucher_id`, `shop_id`, `merchant_id`, `amount`, `status`, `expire_at`, `paid_at`, `closed_at`, `used_at`, `refunded_at`, `release_pending`, `version`, `create_time`, `update_time` FROM `${legacySchema}`.`trade_order_pre_shard` WHERE `user_id` % 8 = 1;

INSERT INTO `trade_order_1` (`order_no`, `user_id`, `voucher_id`, `shop_id`, `merchant_id`, `amount`, `status`, `expire_at`, `paid_at`, `closed_at`, `used_at`, `refunded_at`, `release_pending`, `version`, `create_time`, `update_time`)
SELECT `order_no`, `user_id`, `voucher_id`, `shop_id`, `merchant_id`, `amount`, `status`, `expire_at`, `paid_at`, `closed_at`, `used_at`, `refunded_at`, `release_pending`, `version`, `create_time`, `update_time` FROM `${legacySchema}`.`trade_order_pre_shard` WHERE `user_id` % 8 = 3;

INSERT INTO `trade_order_2` (`order_no`, `user_id`, `voucher_id`, `shop_id`, `merchant_id`, `amount`, `status`, `expire_at`, `paid_at`, `closed_at`, `used_at`, `refunded_at`, `release_pending`, `version`, `create_time`, `update_time`)
SELECT `order_no`, `user_id`, `voucher_id`, `shop_id`, `merchant_id`, `amount`, `status`, `expire_at`, `paid_at`, `closed_at`, `used_at`, `refunded_at`, `release_pending`, `version`, `create_time`, `update_time` FROM `${legacySchema}`.`trade_order_pre_shard` WHERE `user_id` % 8 = 5;

INSERT INTO `trade_order_3` (`order_no`, `user_id`, `voucher_id`, `shop_id`, `merchant_id`, `amount`, `status`, `expire_at`, `paid_at`, `closed_at`, `used_at`, `refunded_at`, `release_pending`, `version`, `create_time`, `update_time`)
SELECT `order_no`, `user_id`, `voucher_id`, `shop_id`, `merchant_id`, `amount`, `status`, `expire_at`, `paid_at`, `closed_at`, `used_at`, `refunded_at`, `release_pending`, `version`, `create_time`, `update_time` FROM `${legacySchema}`.`trade_order_pre_shard` WHERE `user_id` % 8 = 7;

INSERT INTO `order_state_log_0` (`id`, `order_no`, `from_status`, `to_status`, `event`, `operator`, `create_time`)
SELECT `id`, `order_no`, `from_status`, `to_status`, `event`, `operator`, `create_time` FROM `${legacySchema}`.`order_state_log_pre_shard` WHERE `order_no` % 8 = 1;

INSERT INTO `order_state_log_1` (`id`, `order_no`, `from_status`, `to_status`, `event`, `operator`, `create_time`)
SELECT `id`, `order_no`, `from_status`, `to_status`, `event`, `operator`, `create_time` FROM `${legacySchema}`.`order_state_log_pre_shard` WHERE `order_no` % 8 = 3;

INSERT INTO `order_state_log_2` (`id`, `order_no`, `from_status`, `to_status`, `event`, `operator`, `create_time`)
SELECT `id`, `order_no`, `from_status`, `to_status`, `event`, `operator`, `create_time` FROM `${legacySchema}`.`order_state_log_pre_shard` WHERE `order_no` % 8 = 5;

INSERT INTO `order_state_log_3` (`id`, `order_no`, `from_status`, `to_status`, `event`, `operator`, `create_time`)
SELECT `id`, `order_no`, `from_status`, `to_status`, `event`, `operator`, `create_time` FROM `${legacySchema}`.`order_state_log_pre_shard` WHERE `order_no` % 8 = 7;

INSERT INTO `payment_record_0` (`id`, `pay_no`, `order_no`, `user_id`, `amount`, `channel`, `status`, `channel_txn_no`, `paid_amount`, `paid_at`, `create_time`, `update_time`)
SELECT `id`, `pay_no`, `order_no`, `user_id`, `amount`, `channel`, `status`, `channel_txn_no`, `paid_amount`, `paid_at`, `create_time`, `update_time` FROM `${legacySchema}`.`payment_record_pre_shard` WHERE `order_no` % 8 = 1;

INSERT INTO `payment_record_1` (`id`, `pay_no`, `order_no`, `user_id`, `amount`, `channel`, `status`, `channel_txn_no`, `paid_amount`, `paid_at`, `create_time`, `update_time`)
SELECT `id`, `pay_no`, `order_no`, `user_id`, `amount`, `channel`, `status`, `channel_txn_no`, `paid_amount`, `paid_at`, `create_time`, `update_time` FROM `${legacySchema}`.`payment_record_pre_shard` WHERE `order_no` % 8 = 3;

INSERT INTO `payment_record_2` (`id`, `pay_no`, `order_no`, `user_id`, `amount`, `channel`, `status`, `channel_txn_no`, `paid_amount`, `paid_at`, `create_time`, `update_time`)
SELECT `id`, `pay_no`, `order_no`, `user_id`, `amount`, `channel`, `status`, `channel_txn_no`, `paid_amount`, `paid_at`, `create_time`, `update_time` FROM `${legacySchema}`.`payment_record_pre_shard` WHERE `order_no` % 8 = 5;

INSERT INTO `payment_record_3` (`id`, `pay_no`, `order_no`, `user_id`, `amount`, `channel`, `status`, `channel_txn_no`, `paid_amount`, `paid_at`, `create_time`, `update_time`)
SELECT `id`, `pay_no`, `order_no`, `user_id`, `amount`, `channel`, `status`, `channel_txn_no`, `paid_amount`, `paid_at`, `create_time`, `update_time` FROM `${legacySchema}`.`payment_record_pre_shard` WHERE `order_no` % 8 = 7;

INSERT INTO `refund_record_0` (`id`, `refund_no`, `order_no`, `pay_no`, `user_id`, `amount`, `type`, `reason`, `status`, `channel_refund_no`, `request_attempts`, `finished_at`, `create_time`, `update_time`)
SELECT `id`, `refund_no`, `order_no`, `pay_no`, `user_id`, `amount`, `type`, `reason`, `status`, `channel_refund_no`, `request_attempts`, `finished_at`, `create_time`, `update_time` FROM `${legacySchema}`.`refund_record_pre_shard` WHERE `order_no` % 8 = 1;

INSERT INTO `refund_record_1` (`id`, `refund_no`, `order_no`, `pay_no`, `user_id`, `amount`, `type`, `reason`, `status`, `channel_refund_no`, `request_attempts`, `finished_at`, `create_time`, `update_time`)
SELECT `id`, `refund_no`, `order_no`, `pay_no`, `user_id`, `amount`, `type`, `reason`, `status`, `channel_refund_no`, `request_attempts`, `finished_at`, `create_time`, `update_time` FROM `${legacySchema}`.`refund_record_pre_shard` WHERE `order_no` % 8 = 3;

INSERT INTO `refund_record_2` (`id`, `refund_no`, `order_no`, `pay_no`, `user_id`, `amount`, `type`, `reason`, `status`, `channel_refund_no`, `request_attempts`, `finished_at`, `create_time`, `update_time`)
SELECT `id`, `refund_no`, `order_no`, `pay_no`, `user_id`, `amount`, `type`, `reason`, `status`, `channel_refund_no`, `request_attempts`, `finished_at`, `create_time`, `update_time` FROM `${legacySchema}`.`refund_record_pre_shard` WHERE `order_no` % 8 = 5;

INSERT INTO `refund_record_3` (`id`, `refund_no`, `order_no`, `pay_no`, `user_id`, `amount`, `type`, `reason`, `status`, `channel_refund_no`, `request_attempts`, `finished_at`, `create_time`, `update_time`)
SELECT `id`, `refund_no`, `order_no`, `pay_no`, `user_id`, `amount`, `type`, `reason`, `status`, `channel_refund_no`, `request_attempts`, `finished_at`, `create_time`, `update_time` FROM `${legacySchema}`.`refund_record_pre_shard` WHERE `order_no` % 8 = 7;

INSERT INTO `user_coupon_0` (`id`, `coupon_no`, `user_id`, `voucher_id`, `merchant_id`, `shop_id`, `source`, `source_ref`, `status`, `valid_from`, `valid_to`, `verify_code`, `used_at`, `verified_by`, `create_time`, `update_time`)
SELECT `id`, `coupon_no`, `user_id`, `voucher_id`, `merchant_id`, `shop_id`, `source`, `source_ref`, `status`, `valid_from`, `valid_to`, `verify_code`, `used_at`, `verified_by`, `create_time`, `update_time` FROM `${legacySchema}`.`user_coupon_pre_shard` WHERE `user_id` % 8 = 1;

INSERT INTO `user_coupon_1` (`id`, `coupon_no`, `user_id`, `voucher_id`, `merchant_id`, `shop_id`, `source`, `source_ref`, `status`, `valid_from`, `valid_to`, `verify_code`, `used_at`, `verified_by`, `create_time`, `update_time`)
SELECT `id`, `coupon_no`, `user_id`, `voucher_id`, `merchant_id`, `shop_id`, `source`, `source_ref`, `status`, `valid_from`, `valid_to`, `verify_code`, `used_at`, `verified_by`, `create_time`, `update_time` FROM `${legacySchema}`.`user_coupon_pre_shard` WHERE `user_id` % 8 = 3;

INSERT INTO `user_coupon_2` (`id`, `coupon_no`, `user_id`, `voucher_id`, `merchant_id`, `shop_id`, `source`, `source_ref`, `status`, `valid_from`, `valid_to`, `verify_code`, `used_at`, `verified_by`, `create_time`, `update_time`)
SELECT `id`, `coupon_no`, `user_id`, `voucher_id`, `merchant_id`, `shop_id`, `source`, `source_ref`, `status`, `valid_from`, `valid_to`, `verify_code`, `used_at`, `verified_by`, `create_time`, `update_time` FROM `${legacySchema}`.`user_coupon_pre_shard` WHERE `user_id` % 8 = 5;

INSERT INTO `user_coupon_3` (`id`, `coupon_no`, `user_id`, `voucher_id`, `merchant_id`, `shop_id`, `source`, `source_ref`, `status`, `valid_from`, `valid_to`, `verify_code`, `used_at`, `verified_by`, `create_time`, `update_time`)
SELECT `id`, `coupon_no`, `user_id`, `voucher_id`, `merchant_id`, `shop_id`, `source`, `source_ref`, `status`, `valid_from`, `valid_to`, `verify_code`, `used_at`, `verified_by`, `create_time`, `update_time` FROM `${legacySchema}`.`user_coupon_pre_shard` WHERE `user_id` % 8 = 7;

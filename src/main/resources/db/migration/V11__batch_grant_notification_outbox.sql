-- M6C: immutable manual-tag batch grant jobs and the grant notification outbox.

ALTER TABLE `tb_voucher_grant`
  MODIFY COLUMN `source` enum('USER_CLAIM','ADMIN_GRANT','TASK_REWARD','BATCH_GRANT')
    CHARACTER SET ascii COLLATE ascii_bin NOT NULL;

CREATE TABLE `tb_voucher_batch_job` (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT,
  `merchant_id` bigint(20) UNSIGNED NOT NULL,
  `campaign_id` bigint(20) UNSIGNED NOT NULL,
  `operator_id` bigint(20) UNSIGNED NOT NULL,
  `request_id` varchar(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `captured_rule_version` bigint(20) UNSIGNED NOT NULL,
  `target_tag_id` bigint(20) UNSIGNED NOT NULL,
  `status` enum('SNAPSHOTTING','READY','RUNNING','PAUSED','COMPLETED','PARTIAL_FAILED')
    CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'SNAPSHOTTING',
  `target_count` int(10) UNSIGNED NOT NULL DEFAULT 0,
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `start_time` datetime NULL DEFAULT NULL,
  `finish_time` datetime NULL DEFAULT NULL,
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_voucher_batch_job_merchant_request` (`merchant_id`, `request_id`),
  KEY `idx_voucher_batch_job_status_id` (`status`, `id`),
  KEY `idx_voucher_batch_job_merchant_status_id` (`merchant_id`, `status`, `id`),
  KEY `idx_voucher_batch_job_campaign_status_id` (`campaign_id`, `status`, `id`),
  KEY `idx_voucher_batch_job_operator_id` (`operator_id`, `id`),
  CONSTRAINT `fk_voucher_batch_job_merchant` FOREIGN KEY (`merchant_id`) REFERENCES `tb_merchant` (`id`),
  CONSTRAINT `fk_voucher_batch_job_campaign_scope` FOREIGN KEY (`campaign_id`, `merchant_id`)
    REFERENCES `tb_voucher_campaign` (`id`, `merchant_id`),
  CONSTRAINT `fk_voucher_batch_job_operator` FOREIGN KEY (`operator_id`) REFERENCES `tb_admin_account` (`id`),
  CONSTRAINT `fk_voucher_batch_job_tag_scope` FOREIGN KEY (`target_tag_id`, `merchant_id`)
    REFERENCES `tb_marketing_tag` (`id`, `merchant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='人工标签批量发券 Job';

CREATE TABLE `tb_voucher_batch_item` (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT,
  `job_id` bigint(20) UNSIGNED NOT NULL,
  `user_id` bigint(20) UNSIGNED NOT NULL,
  `status` enum('PENDING','GRANTED','IDEMPOTENT','SKIPPED','FAILED')
    CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PENDING',
  `grant_id` bigint(20) UNSIGNED NULL DEFAULT NULL,
  `attempts` int(10) UNSIGNED NOT NULL DEFAULT 0,
  `last_error_code` varchar(64) CHARACTER SET ascii COLLATE ascii_bin NULL DEFAULT NULL,
  `last_error_message` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_voucher_batch_item_job_user` (`job_id`, `user_id`),
  KEY `idx_voucher_batch_item_job_status_id` (`job_id`, `status`, `id`),
  KEY `idx_voucher_batch_item_job_id` (`job_id`, `id`),
  KEY `idx_voucher_batch_item_grant_id` (`grant_id`),
  CONSTRAINT `fk_voucher_batch_item_job` FOREIGN KEY (`job_id`)
    REFERENCES `tb_voucher_batch_job` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_voucher_batch_item_user` FOREIGN KEY (`user_id`) REFERENCES `tb_user` (`id`),
  CONSTRAINT `fk_voucher_batch_item_grant` FOREIGN KEY (`grant_id`)
    REFERENCES `tb_voucher_grant` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='批量发券目标与结果快照';

CREATE TABLE `tb_voucher_grant_notification_outbox` (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT,
  `grant_id` bigint(20) UNSIGNED NOT NULL,
  `merchant_id` bigint(20) UNSIGNED NOT NULL,
  `user_id` bigint(20) UNSIGNED NOT NULL,
  `event_type` enum('VOUCHER_GRANTED') CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `status` enum('PENDING','PUBLISHED') CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PENDING',
  `attempts` int(10) UNSIGNED NOT NULL DEFAULT 0,
  `next_attempt_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `last_error` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `published_at` datetime NULL DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_voucher_grant_notification_grant` (`grant_id`),
  KEY `idx_voucher_grant_notification_pending_next_id` (`status`, `next_attempt_time`, `id`),
  KEY `idx_voucher_grant_notification_merchant_status_id` (`merchant_id`, `status`, `id`),
  CONSTRAINT `fk_voucher_grant_notification_grant` FOREIGN KEY (`grant_id`)
    REFERENCES `tb_voucher_grant` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_voucher_grant_notification_merchant` FOREIGN KEY (`merchant_id`)
    REFERENCES `tb_merchant` (`id`),
  CONSTRAINT `fk_voucher_grant_notification_user` FOREIGN KEY (`user_id`)
    REFERENCES `tb_user` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='优惠券发放通知事务 Outbox';

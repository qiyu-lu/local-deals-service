CREATE TABLE `tb_marketing_tag` (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT,
  `merchant_id` bigint(20) UNSIGNED NOT NULL,
  `code` varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `status` enum('ACTIVE','DISABLED') CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'ACTIVE',
  `created_by` bigint(20) UNSIGNED NOT NULL,
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_marketing_tag_merchant_code` (`merchant_id`, `code`),
  UNIQUE KEY `uk_marketing_tag_id_merchant` (`id`, `merchant_id`),
  KEY `idx_marketing_tag_merchant_status_id` (`merchant_id`, `status`, `id`),
  CONSTRAINT `fk_marketing_tag_merchant` FOREIGN KEY (`merchant_id`) REFERENCES `tb_merchant` (`id`),
  CONSTRAINT `fk_marketing_tag_creator` FOREIGN KEY (`created_by`) REFERENCES `tb_admin_account` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='商户人工营销标签';

CREATE TABLE `tb_marketing_tag_member` (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT,
  `merchant_id` bigint(20) UNSIGNED NOT NULL,
  `tag_id` bigint(20) UNSIGNED NOT NULL,
  `user_id` bigint(20) UNSIGNED NOT NULL,
  `status` enum('ACTIVE','REMOVED') CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'ACTIVE',
  `expire_time` datetime NULL DEFAULT NULL,
  `assigned_by` bigint(20) UNSIGNED NOT NULL,
  `assigned_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_marketing_tag_member_tag_user` (`tag_id`, `user_id`),
  KEY `idx_tag_member_merchant_user_status` (`merchant_id`, `user_id`, `status`),
  KEY `idx_tag_member_tag_merchant` (`tag_id`, `merchant_id`),
  CONSTRAINT `fk_tag_member_tag_scope` FOREIGN KEY (`tag_id`, `merchant_id`)
    REFERENCES `tb_marketing_tag` (`id`, `merchant_id`),
  CONSTRAINT `fk_tag_member_user` FOREIGN KEY (`user_id`) REFERENCES `tb_user` (`id`),
  CONSTRAINT `fk_tag_member_assigner` FOREIGN KEY (`assigned_by`) REFERENCES `tb_admin_account` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='人工标签成员审计关系';

CREATE TABLE `tb_voucher_campaign` (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT,
  `merchant_id` bigint(20) UNSIGNED NOT NULL,
  `voucher_id` bigint(20) UNSIGNED NOT NULL,
  `name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `grant_mode` enum('CLAIM','ADMIN','BOTH') CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `eligibility_type` enum('ALL','MANUAL_TAG') CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `required_tag_id` bigint(20) UNSIGNED NULL DEFAULT NULL,
  `begin_time` datetime NOT NULL,
  `end_time` datetime NOT NULL,
  `quota_total` int(10) UNSIGNED NOT NULL,
  `granted_count` int(10) UNSIGNED NOT NULL DEFAULT 0,
  `status` enum('DRAFT','ACTIVE','PAUSED','CLOSED') CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'DRAFT',
  `rule_version` bigint(20) UNSIGNED NOT NULL DEFAULT 1,
  `created_by` bigint(20) UNSIGNED NOT NULL,
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_voucher_campaign_id_merchant` (`id`, `merchant_id`),
  UNIQUE KEY `uk_voucher_campaign_id_merchant_voucher` (`id`, `merchant_id`, `voucher_id`),
  KEY `idx_campaign_merchant_status_time` (`merchant_id`, `status`, `begin_time`, `end_time`, `id`),
  KEY `idx_campaign_voucher` (`voucher_id`, `id`),
  KEY `idx_campaign_tag_scope` (`required_tag_id`, `merchant_id`),
  CONSTRAINT `fk_campaign_merchant` FOREIGN KEY (`merchant_id`) REFERENCES `tb_merchant` (`id`),
  CONSTRAINT `fk_campaign_voucher` FOREIGN KEY (`voucher_id`) REFERENCES `tb_voucher` (`id`),
  CONSTRAINT `fk_campaign_required_tag_scope` FOREIGN KEY (`required_tag_id`, `merchant_id`)
    REFERENCES `tb_marketing_tag` (`id`, `merchant_id`),
  CONSTRAINT `fk_campaign_creator` FOREIGN KEY (`created_by`) REFERENCES `tb_admin_account` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='普通券定向发放活动';

CREATE TABLE `tb_voucher_grant` (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT,
  `campaign_id` bigint(20) UNSIGNED NOT NULL,
  `merchant_id` bigint(20) UNSIGNED NOT NULL,
  `voucher_id` bigint(20) UNSIGNED NOT NULL,
  `user_id` bigint(20) UNSIGNED NOT NULL,
  `source` enum('USER_CLAIM','ADMIN_GRANT') CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `rule_version` bigint(20) UNSIGNED NOT NULL,
  `operator_id` bigint(20) UNSIGNED NULL DEFAULT NULL COMMENT 'ADMIN_GRANT 的后台账号，用户领取为空',
  `granted_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_voucher_grant_campaign_user` (`campaign_id`, `user_id`),
  KEY `idx_voucher_grant_user_time` (`user_id`, `granted_at`, `id`),
  KEY `idx_voucher_grant_merchant_campaign` (`merchant_id`, `campaign_id`, `id`),
  KEY `idx_voucher_grant_campaign_scope` (`campaign_id`, `merchant_id`),
  CONSTRAINT `fk_voucher_grant_campaign_scope` FOREIGN KEY (`campaign_id`, `merchant_id`, `voucher_id`)
    REFERENCES `tb_voucher_campaign` (`id`, `merchant_id`, `voucher_id`),
  CONSTRAINT `fk_voucher_grant_voucher` FOREIGN KEY (`voucher_id`) REFERENCES `tb_voucher` (`id`),
  CONSTRAINT `fk_voucher_grant_user` FOREIGN KEY (`user_id`) REFERENCES `tb_user` (`id`),
  CONSTRAINT `fk_voucher_grant_operator` FOREIGN KEY (`operator_id`) REFERENCES `tb_admin_account` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='M6A 唯一用户持券事实';

INSERT INTO `tb_admin_permission` (`code`, `name`, `module`) VALUES
  ('marketing:read', '查看营销标签与活动', 'marketing'),
  ('marketing:write', '维护营销标签与活动', 'marketing');

INSERT INTO `tb_admin_role_permission` (`role_id`, `permission_code`)
SELECT r.`id`, p.`code`
FROM `tb_admin_role` r
JOIN `tb_admin_permission` p ON p.`code` IN ('marketing:read', 'marketing:write')
WHERE r.`code` IN ('PLATFORM_ADMIN', 'MERCHANT_OWNER');

INSERT INTO `tb_admin_role_permission` (`role_id`, `permission_code`)
SELECT r.`id`, p.`code`
FROM `tb_admin_role` r
JOIN `tb_admin_permission` p ON p.`code` = 'marketing:read'
WHERE r.`code` = 'MERCHANT_STAFF';

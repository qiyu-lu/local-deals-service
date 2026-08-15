CREATE TABLE `tb_merchant` (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '商户主体主键',
  `code` varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '稳定商户编码',
  `name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '商户名称',
  `status` tinyint(3) UNSIGNED NOT NULL DEFAULT 1 COMMENT '0待分配 1启用 2停用',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_merchant_code` (`code`),
  KEY `idx_merchant_status_id` (`status`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='商户主体';

CREATE TABLE `tb_admin_account` (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '后台账号主键',
  `merchant_id` bigint(20) UNSIGNED NULL COMMENT '平台账号为空，商户账号指向所属商户',
  `username` varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `password_hash` varchar(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `display_name` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `scope_type` varchar(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'PLATFORM或MERCHANT',
  `status` tinyint(3) UNSIGNED NOT NULL DEFAULT 1 COMMENT '1启用 2停用',
  `last_login_time` timestamp NULL DEFAULT NULL,
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_admin_account_username` (`username`),
  KEY `idx_admin_account_merchant_status` (`merchant_id`, `status`, `id`),
  CONSTRAINT `fk_admin_account_merchant` FOREIGN KEY (`merchant_id`) REFERENCES `tb_merchant` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='独立后台账号';

CREATE TABLE `tb_admin_role` (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT,
  `code` varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `name` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `scope_type` varchar(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'PLATFORM或MERCHANT',
  `built_in` tinyint(1) UNSIGNED NOT NULL DEFAULT 1,
  `status` tinyint(3) UNSIGNED NOT NULL DEFAULT 1,
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_admin_role_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='后台角色';

CREATE TABLE `tb_admin_permission` (
  `code` varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `name` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `module` varchar(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `status` tinyint(3) UNSIGNED NOT NULL DEFAULT 1,
  PRIMARY KEY (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='后台权限';

CREATE TABLE `tb_admin_account_role` (
  `account_id` bigint(20) UNSIGNED NOT NULL,
  `role_id` bigint(20) UNSIGNED NOT NULL,
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`account_id`, `role_id`),
  KEY `idx_admin_account_role_role` (`role_id`, `account_id`),
  CONSTRAINT `fk_admin_account_role_account` FOREIGN KEY (`account_id`) REFERENCES `tb_admin_account` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_admin_account_role_role` FOREIGN KEY (`role_id`) REFERENCES `tb_admin_role` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='后台账号角色';

CREATE TABLE `tb_admin_role_permission` (
  `role_id` bigint(20) UNSIGNED NOT NULL,
  `permission_code` varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  PRIMARY KEY (`role_id`, `permission_code`),
  KEY `idx_admin_role_permission_permission` (`permission_code`, `role_id`),
  CONSTRAINT `fk_admin_role_permission_role` FOREIGN KEY (`role_id`) REFERENCES `tb_admin_role` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_admin_role_permission_permission` FOREIGN KEY (`permission_code`) REFERENCES `tb_admin_permission` (`code`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='后台角色权限';

INSERT INTO `tb_merchant` (`code`, `name`, `status`)
VALUES ('LEGACY_UNASSIGNED', '历史商铺待分配', 0);

ALTER TABLE `tb_shop`
  ADD COLUMN `merchant_id` bigint(20) UNSIGNED NULL COMMENT '所属商户' AFTER `id`;

UPDATE `tb_shop`
SET `merchant_id` = (SELECT `id` FROM `tb_merchant` WHERE `code` = 'LEGACY_UNASSIGNED')
WHERE `merchant_id` IS NULL;

ALTER TABLE `tb_shop`
  MODIFY COLUMN `merchant_id` bigint(20) UNSIGNED NOT NULL COMMENT '所属商户',
  ADD KEY `idx_shop_merchant_id` (`merchant_id`, `id`),
  ADD CONSTRAINT `fk_shop_merchant` FOREIGN KEY (`merchant_id`) REFERENCES `tb_merchant` (`id`);

ALTER TABLE `tb_voucher`
  ADD KEY `idx_voucher_shop_status_id` (`shop_id`, `status`, `id`);

ALTER TABLE `tb_voucher_order`
  ADD KEY `idx_voucher_order_voucher_status_time` (`voucher_id`, `status`, `create_time`);

INSERT INTO `tb_admin_role` (`code`, `name`, `scope_type`) VALUES
  ('PLATFORM_ADMIN', '平台管理员', 'PLATFORM'),
  ('MERCHANT_OWNER', '商户主账号', 'MERCHANT'),
  ('MERCHANT_STAFF', '商户员工', 'MERCHANT');

INSERT INTO `tb_admin_permission` (`code`, `name`, `module`) VALUES
  ('dashboard:read', '查看仪表盘', 'dashboard'),
  ('shop:read', '查看商铺', 'shop'),
  ('shop:write', '维护商铺', 'shop'),
  ('voucher:read', '查看优惠券', 'voucher'),
  ('voucher:write', '维护优惠券', 'voucher'),
  ('order:read', '查看订单', 'order'),
  ('order:realtime', '接收实时订单', 'order'),
  ('account:read', '查看后台账号', 'account'),
  ('account:manage', '管理后台账号', 'account'),
  ('merchant:manage', '管理商户主体', 'merchant');

INSERT INTO `tb_admin_role_permission` (`role_id`, `permission_code`)
SELECT r.`id`, p.`code`
FROM `tb_admin_role` r
CROSS JOIN `tb_admin_permission` p
WHERE r.`code` = 'PLATFORM_ADMIN';

INSERT INTO `tb_admin_role_permission` (`role_id`, `permission_code`)
SELECT r.`id`, p.`code`
FROM `tb_admin_role` r
JOIN `tb_admin_permission` p ON p.`code` IN (
  'dashboard:read', 'shop:read', 'shop:write', 'voucher:read', 'voucher:write',
  'order:read', 'order:realtime', 'account:read', 'account:manage'
)
WHERE r.`code` = 'MERCHANT_OWNER';

INSERT INTO `tb_admin_role_permission` (`role_id`, `permission_code`)
SELECT r.`id`, p.`code`
FROM `tb_admin_role` r
JOIN `tb_admin_permission` p ON p.`code` IN (
  'dashboard:read', 'shop:read', 'voucher:read', 'order:read', 'order:realtime'
)
WHERE r.`code` = 'MERCHANT_STAFF';

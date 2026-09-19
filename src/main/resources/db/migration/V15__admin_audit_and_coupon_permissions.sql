-- M2: back-office operation audit and the permissions of the new order/coupon endpoints.

CREATE TABLE `admin_audit_log` (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT,
  `account_id` bigint(20) UNSIGNED NOT NULL,
  `merchant_id` bigint(20) UNSIGNED NULL DEFAULT NULL COMMENT '平台账号为空',
  `username` varchar(64) CHARACTER SET ascii COLLATE ascii_bin NULL DEFAULT NULL,
  `action` varchar(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
    COMMENT '显式动作名，或 <HTTP 方法> <路由模板>',
  `target_type` varchar(32) CHARACTER SET ascii COLLATE ascii_bin NULL DEFAULT NULL,
  `target_id` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL,
  `result` enum('SUCCESS','FAILURE') CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `error_code` varchar(64) CHARACTER SET ascii COLLATE ascii_bin NULL DEFAULT NULL,
  `client_ip` varchar(64) CHARACTER SET ascii COLLATE ascii_bin NULL DEFAULT NULL,
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_admin_audit_log_merchant` (`merchant_id`, `id`),
  KEY `idx_admin_audit_log_account` (`account_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='后台操作审计';

INSERT INTO `tb_admin_permission` (`code`, `name`, `module`) VALUES
  ('coupon:verify', '到店核销', 'coupon'),
  ('audit:read', '查看操作审计', 'audit');

-- Verifying at the counter is staff work; the audit trail is for owners and the platform.
INSERT INTO `tb_admin_role_permission` (`role_id`, `permission_code`)
SELECT r.`id`, 'coupon:verify' FROM `tb_admin_role` r
WHERE r.`code` IN ('PLATFORM_ADMIN', 'MERCHANT_OWNER', 'MERCHANT_STAFF');

INSERT INTO `tb_admin_role_permission` (`role_id`, `permission_code`)
SELECT r.`id`, 'audit:read' FROM `tb_admin_role` r
WHERE r.`code` IN ('PLATFORM_ADMIN', 'MERCHANT_OWNER');

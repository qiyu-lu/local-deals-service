ALTER TABLE `tb_admin_account`
  ADD COLUMN `auth_version` int(10) UNSIGNED NOT NULL DEFAULT 0
  COMMENT '凭据版本，修改密码或安全状态时递增以撤销旧会话'
  AFTER `status`;

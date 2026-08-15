CREATE TABLE `tb_upload_file` (
  `path` varchar(255) NOT NULL COMMENT '相对于图片根目录的受管路径',
  `owner_user_id` bigint(20) UNSIGNED NOT NULL COMMENT '上传用户',
  `status` varchar(16) NOT NULL COMMENT 'TEMP 或 PUBLISHED',
  `blog_id` bigint(20) UNSIGNED NULL COMMENT '发布后关联的笔记',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`path`),
  INDEX `idx_upload_owner_status` (`owner_user_id`, `status`),
  INDEX `idx_upload_blog` (`blog_id`)
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci
  COMMENT = '用户上传图片的归属与发布状态';

-- Durable user-to-blog state. Redis is a rebuildable projection only.
CREATE TABLE tb_blog_like (
    blog_id BIGINT UNSIGNED NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    liked_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (blog_id, user_id),
    INDEX idx_blog_like_rank (blog_id, liked_at, user_id),
    INDEX idx_blog_like_user (user_id, blog_id),
    CONSTRAINT fk_blog_like_blog
        FOREIGN KEY (blog_id) REFERENCES tb_blog (id) ON DELETE CASCADE,
    CONSTRAINT fk_blog_like_user
        FOREIGN KEY (user_id) REFERENCES tb_user (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci
  COMMENT = 'Durable active blog-like relationships';

-- Preserve unattributed counts from the legacy data set. They cannot be
-- converted into user identities without an explicit old-Redis import.
ALTER TABLE tb_blog
    ADD COLUMN legacy_liked_offset INT UNSIGNED NOT NULL DEFAULT 0
        COMMENT 'Pre-V8 likes without durable user attribution' AFTER liked;

UPDATE tb_blog
SET legacy_liked_offset = liked;

-- Relationship changes and their aggregate deltas are committed together.
-- The worker updates tb_blog and marks these rows processed in one transaction.
CREATE TABLE tb_blog_like_outbox (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    blog_id BIGINT UNSIGNED NOT NULL,
    delta TINYINT NOT NULL COMMENT '1 for like, -1 for unlike',
    create_time TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    processed_time TIMESTAMP(3) NULL DEFAULT NULL,
    PRIMARY KEY (id),
    INDEX idx_blog_like_outbox_pending (processed_time, id),
    INDEX idx_blog_like_outbox_blog (blog_id, id),
    CONSTRAINT chk_blog_like_outbox_delta CHECK (delta IN (-1, 1)),
    CONSTRAINT fk_blog_like_outbox_blog
        FOREIGN KEY (blog_id) REFERENCES tb_blog (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci
  COMMENT = 'Transactional blog-like aggregate outbox';

-- Durable proof that the stopped-write legacy identity cutover completed. Production write and
-- worker gates refuse to open until the importer records this marker after invariant checks.
CREATE TABLE tb_blog_like_cutover (
    marker_key VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    source_key_count INT UNSIGNED NOT NULL,
    source_member_count BIGINT UNSIGNED NOT NULL,
    imported_count BIGINT UNSIGNED NOT NULL,
    completed_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (marker_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci
  COMMENT = 'Auditable completion marker for the V8 legacy-like cutover';

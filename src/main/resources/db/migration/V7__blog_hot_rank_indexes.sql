-- Stabilize hot-blog pagination and make the database fallback indexable.
-- Keep this migration independent from the later like-write cutover so the
-- read projection can be deployed and verified first.

UPDATE tb_blog
SET liked = 0
WHERE liked IS NULL;

ALTER TABLE tb_blog
    MODIFY COLUMN liked INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '点赞数量',
    ADD INDEX idx_blog_hot (liked DESC, id DESC),
    ADD INDEX idx_blog_shop_hot (shop_id, liked DESC, id DESC);

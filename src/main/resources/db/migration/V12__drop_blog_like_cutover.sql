-- V2 has no pre-V8 production data: the stopped-write legacy-like import, its completion marker
-- and the unattributed like offset have nothing left to protect. tb_blog.liked is now exactly the
-- outbox-maintained count of tb_blog_like rows.
DROP TABLE IF EXISTS tb_blog_like_cutover;

ALTER TABLE tb_blog
    DROP COLUMN legacy_liked_offset;

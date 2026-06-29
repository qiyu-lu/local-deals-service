package com.localdeals.service;

import com.localdeals.dto.Result;
import com.localdeals.entity.Blog;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for IBlogService.
 * {@code @Transactional} ensures all DB changes in each test are rolled back automatically.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class BlogServiceIT {

    @Resource
    private IBlogService blogService;

    @Resource
    private JdbcTemplate jdbcTemplate;

    /**
     * Bug B2: BlogServiceImpl.queryBlogUser() calls user.getNickName() without a null check.
     * When the blog's author has been deleted, userService.getById() returns null → NPE.
     * After fix: null user is skipped, blog.name and blog.icon remain null, no exception.
     */
    @Test
    void queryBlogUser_deletedUser_doesNotThrowNPE() {
        long ghostUserId = Long.MAX_VALUE; // no such user in DB

        jdbcTemplate.update(
                "INSERT INTO tb_blog (shop_id, user_id, title, images, content) " +
                "VALUES (1, ?, 'Test Blog', '', 'Test content')",
                ghostUserId);
        Long blogId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        assertNotNull(blogId);

        Result result = blogService.queryBlogById(blogId);

        assertTrue(result.getSuccess(), "queryBlogById must succeed even when author is deleted");
        Blog blog = (Blog) result.getData();
        assertNotNull(blog);
        assertNull(blog.getName(),
                "blog.name must be null when user not found (not throw NPE)");
    }
}

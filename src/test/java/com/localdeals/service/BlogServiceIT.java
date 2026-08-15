package com.localdeals.service;

import com.localdeals.dto.Result;
import com.localdeals.dto.UserDTO;
import com.localdeals.entity.Blog;
import com.localdeals.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
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

    @Resource
    private UploadFileService uploadFileService;

    @AfterEach
    void clearUserContext() {
        UserHolder.removeUser();
    }

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

    @Test
    void saveBlog_publishesOnlyCurrentUsersTemporaryImagesInSameTransaction() {
        long ownerId = 700002L;
        String imagePath = "blogs/1/2/123e4567-e89b-12d3-a456-426614174003.png";
        UserDTO user = new UserDTO();
        user.setId(ownerId);
        UserHolder.saveUser(user);
        uploadFileService.registerTemporary(imagePath, ownerId);

        Blog blog = new Blog()
                .setShopId(1L)
                .setTitle("Owned image publication")
                .setImages("/imgs/" + imagePath)
                .setContent("The upload record must be published with the saved blog.");

        Result result = blogService.saveBlog(blog);

        assertTrue(result.getSuccess());
        assertNotNull(blog.getId());
        assertEquals("PUBLISHED", jdbcTemplate.queryForObject(
                "SELECT status FROM tb_upload_file WHERE path = ?", String.class, imagePath));
        assertEquals(blog.getId(), jdbcTemplate.queryForObject(
                "SELECT blog_id FROM tb_upload_file WHERE path = ?", Long.class, imagePath));
    }
}

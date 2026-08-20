package com.localdeals.service;

import com.localdeals.dto.BlogLikeCommandResult;
import com.localdeals.dto.Result;
import com.localdeals.dto.UserDTO;
import com.localdeals.entity.Blog;
import com.localdeals.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(
        named = "LOCAL_DEALS_RUN_ISOLATED_LIKE_IT", matches = "true")
class BlogLikeReliabilityIT {

    private static final long USER_ID = 1L;

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Resource
    private BlogLikeCommandService commandService;

    @Resource
    private BlogLikeOutboxBatchService batchService;

    @Resource
    private IBlogService blogService;

    private long blogId;
    private String faultTriggerName;

    @BeforeEach
    void createOwnedBlog() {
        blogId = 8_800_000_000_000L + (System.nanoTime() % 1_000_000_000L);
        jdbcTemplate.update(
                "INSERT INTO tb_blog " +
                        "(id, shop_id, user_id, title, images, content, liked, legacy_liked_offset) " +
                        "VALUES (?, 1, ?, 'like reliability', '', 'test', 7, 7)",
                blogId, USER_ID);
    }

    @AfterEach
    void cleanupOwnedRows() {
        try {
            dropFaultTrigger();
        } finally {
            UserHolder.removeUser();
            jdbcTemplate.update("DELETE FROM tb_blog_like_outbox WHERE blog_id = ?", blogId);
            jdbcTemplate.update("DELETE FROM tb_blog_like WHERE blog_id = ?", blogId);
            jdbcTemplate.update("DELETE FROM tb_blog WHERE id = ?", blogId);
        }
    }

    @Test
    void repeatedDesiredCommandsProduceOneDeltaAndPreserveLegacyOffset() {
        BlogLikeCommandResult firstLike = commandService.setLiked(blogId, USER_ID, true);
        BlogLikeCommandResult repeatedLike = commandService.setLiked(blogId, USER_ID, true);

        assertThat(firstLike.isChanged()).isTrue();
        assertThat(repeatedLike.isChanged()).isFalse();
        assertThat(activeRelationships()).isEqualTo(1);
        assertThat(pendingEvents()).isEqualTo(1);

        BlogLikeOutboxBatchService.BatchResult likeBatch = batchService.processNextBatch(100);

        assertThat(likeBatch.getProcessedEvents()).isEqualTo(1);
        assertThat(likedCount()).isEqualTo(8);
        assertThat(likedCount()).isEqualTo(legacyOffset() + activeRelationships());

        BlogLikeOutboxBatchService.BatchResult replayBatch = batchService.processNextBatch(100);
        assertThat(replayBatch.getProcessedEvents()).isZero();
        assertThat(likedCount()).isEqualTo(8);

        BlogLikeCommandResult firstUnlike = commandService.setLiked(blogId, USER_ID, false);
        BlogLikeCommandResult repeatedUnlike = commandService.setLiked(blogId, USER_ID, false);
        assertThat(firstUnlike.isChanged()).isTrue();
        assertThat(repeatedUnlike.isChanged()).isFalse();
        assertThat(pendingEvents()).isEqualTo(1);

        batchService.processNextBatch(100);

        assertThat(likedCount()).isEqualTo(7);
        assertThat(likedCount()).isEqualTo(legacyOffset() + activeRelationships());
    }

    @Test
    @SuppressWarnings("unchecked")
    void userAndShopListsHydrateTheDurableLikeState() {
        UserDTO user = new UserDTO();
        user.setId(USER_ID);
        UserHolder.saveUser(user);
        assertThat(commandService.setLiked(blogId, USER_ID, true).isChanged()).isTrue();

        Result userResult = blogService.queryBlogsByUserId(USER_ID, 1);
        Result shopResult = blogService.queryBlogsByShopId(1L, 1);

        Blog userBlog = findBlog((List<Blog>) userResult.getData(), blogId);
        Blog shopBlog = findBlog((List<Blog>) shopResult.getData(), blogId);
        assertThat(userBlog).isNotNull();
        assertThat(userBlog.getIsLike()).isTrue();
        assertThat(shopBlog).isNotNull();
        assertThat(shopBlog.getIsLike()).isTrue();
    }

    @Test
    void concurrentLikesCreateOneRelationshipAndOneOutboxEvent() throws Exception {
        int callers = 100;
        ExecutorService executor = Executors.newFixedThreadPool(callers);
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<BlogLikeCommandResult>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < callers; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await(10, TimeUnit.SECONDS);
                    return commandService.setLiked(blogId, USER_ID, true);
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            int changed = 0;
            for (Future<BlogLikeCommandResult> future : futures) {
                if (future.get(15, TimeUnit.SECONDS).isChanged()) {
                    changed++;
                }
            }
            assertThat(changed).isEqualTo(1);
            assertThat(activeRelationships()).isEqualTo(1);
            assertThat(pendingEvents()).isEqualTo(1);

            batchService.processNextBatch(100);
            assertThat(likedCount()).isEqualTo(8);

            CountDownLatch unlikeReady = new CountDownLatch(callers);
            CountDownLatch unlikeStart = new CountDownLatch(1);
            List<Future<BlogLikeCommandResult>> unlikeFutures = new ArrayList<>();
            for (int index = 0; index < callers; index++) {
                unlikeFutures.add(executor.submit(() -> {
                    unlikeReady.countDown();
                    unlikeStart.await(10, TimeUnit.SECONDS);
                    return commandService.setLiked(blogId, USER_ID, false);
                }));
            }
            assertThat(unlikeReady.await(10, TimeUnit.SECONDS)).isTrue();
            unlikeStart.countDown();

            int unlikeChanged = 0;
            for (Future<BlogLikeCommandResult> future : unlikeFutures) {
                if (future.get(15, TimeUnit.SECONDS).isChanged()) {
                    unlikeChanged++;
                }
            }
            assertThat(unlikeChanged).isEqualTo(1);
            assertThat(activeRelationships()).isZero();
            assertThat(pendingEvents()).isEqualTo(1);

            batchService.processNextBatch(100);
            assertThat(likedCount()).isEqualTo(7);
            assertThat(likedCount()).isEqualTo(legacyOffset() + activeRelationships());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void outboxInsertFailureRollsBackTheRelationshipMutation() {
        faultTriggerName = "trg_like_it_outbox_" + blogId;
        try {
            jdbcTemplate.execute(
                    "CREATE TRIGGER `" + faultTriggerName + "` " +
                            "BEFORE INSERT ON tb_blog_like_outbox FOR EACH ROW BEGIN " +
                            "IF NEW.blog_id = " + blogId + " THEN " +
                            "SIGNAL SQLSTATE '45000' " +
                            "SET MESSAGE_TEXT = 'isolated like IT injected outbox failure'; " +
                            "END IF; END");

            assertThatThrownBy(() -> commandService.setLiked(blogId, USER_ID, true))
                    .isInstanceOf(DataAccessException.class);

            assertThat(activeRelationships()).isZero();
            assertThat(outboxEvents()).isZero();
            assertThat(likedCount()).isEqualTo(7);
        } finally {
            dropFaultTrigger();
        }
    }

    @Test
    void processedMarkerFailureRollsBackTheAggregateAndRemainsReplayable() {
        assertThat(commandService.setLiked(blogId, USER_ID, true).isChanged()).isTrue();
        faultTriggerName = "trg_like_it_marker_" + blogId;
        try {
            jdbcTemplate.execute(
                    "CREATE TRIGGER `" + faultTriggerName + "` " +
                            "BEFORE UPDATE ON tb_blog_like_outbox FOR EACH ROW BEGIN " +
                            "IF NEW.blog_id = " + blogId + " AND NEW.processed_time IS NOT NULL THEN " +
                            "SIGNAL SQLSTATE '45000' " +
                            "SET MESSAGE_TEXT = 'isolated like IT injected marker failure'; " +
                            "END IF; END");

            assertThatThrownBy(() -> batchService.processNextBatch(100))
                    .isInstanceOf(DataAccessException.class);

            assertThat(likedCount()).isEqualTo(7);
            assertThat(pendingEvents()).isEqualTo(1);
            assertThat(processedEvents()).isZero();
        } finally {
            dropFaultTrigger();
        }

        assertThat(batchService.processNextBatch(100).getProcessedEvents()).isEqualTo(1);
        assertThat(likedCount()).isEqualTo(8);
        assertThat(pendingEvents()).isZero();
    }

    @Test
    void concurrentBatchConsumersApplyOneOutboxEventExactlyOnce() throws Exception {
        assertThat(commandService.setLiked(blogId, USER_ID, true).isChanged()).isTrue();
        assertThat(pendingEvents()).isEqualTo(1);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<BlogLikeOutboxBatchService.BatchResult> first = executor.submit(() -> {
                ready.countDown();
                start.await(10, TimeUnit.SECONDS);
                return batchService.processNextBatch(1);
            });
            Future<BlogLikeOutboxBatchService.BatchResult> second = executor.submit(() -> {
                ready.countDown();
                start.await(10, TimeUnit.SECONDS);
                return batchService.processNextBatch(1);
            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            int processed = first.get(15, TimeUnit.SECONDS).getProcessedEvents()
                    + second.get(15, TimeUnit.SECONDS).getProcessedEvents();

            assertThat(processed).isEqualTo(1);
            assertThat(pendingEvents()).isZero();
            assertThat(processedEvents()).isEqualTo(1);
            assertThat(likedCount()).isEqualTo(8);
            assertThat(likedCount()).isEqualTo(legacyOffset() + activeRelationships());
            assertThat(batchService.processNextBatch(1).getProcessedEvents()).isZero();
        } finally {
            executor.shutdownNow();
        }
    }

    private int activeRelationships() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_blog_like WHERE blog_id = ?", Integer.class, blogId);
    }

    private static Blog findBlog(List<Blog> blogs, long expectedId) {
        return blogs.stream()
                .filter(blog -> blog.getId() != null && blog.getId() == expectedId)
                .findFirst()
                .orElse(null);
    }

    private int pendingEvents() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_blog_like_outbox " +
                        "WHERE blog_id = ? AND processed_time IS NULL", Integer.class, blogId);
    }

    private int processedEvents() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_blog_like_outbox " +
                        "WHERE blog_id = ? AND processed_time IS NOT NULL", Integer.class, blogId);
    }

    private int outboxEvents() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_blog_like_outbox WHERE blog_id = ?", Integer.class, blogId);
    }

    private int likedCount() {
        return jdbcTemplate.queryForObject(
                "SELECT liked FROM tb_blog WHERE id = ?", Integer.class, blogId);
    }

    private int legacyOffset() {
        return jdbcTemplate.queryForObject(
                "SELECT legacy_liked_offset FROM tb_blog WHERE id = ?", Integer.class, blogId);
    }

    private void dropFaultTrigger() {
        if (faultTriggerName == null) {
            return;
        }
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS `" + faultTriggerName + "`");
        faultTriggerName = null;
    }
}

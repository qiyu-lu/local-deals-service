package com.localdeals.service.impl;

import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.localdeals.dto.Result;
import com.localdeals.dto.UserDTO;
import com.localdeals.entity.Blog;
import com.localdeals.entity.Follow;
import com.localdeals.observability.LocalDealsMetrics;
import com.localdeals.mapper.BlogMapper;
import com.localdeals.service.BlogHotRankService;
import com.localdeals.service.IFollowService;
import com.localdeals.service.UploadFileService;
import com.localdeals.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Verifies blog publication hooks against Spring's real transaction-synchronization lifecycle. */
@ExtendWith(MockitoExtension.class)
class BlogServiceAfterCommitTest {

    private static final long USER_ID = 700_101L;

    @Mock
    private BlogMapper blogMapper;
    @Mock
    private UploadFileService uploadFileService;
    @Mock
    private IFollowService followService;
    @Mock
    private QueryChainWrapper<Follow> followQuery;
    @Mock
    private BlogHotRankService hotRankService;
    @Mock
    private StringRedisTemplate redisTemplate;

    private BlogServiceImpl service;
    private TransactionTemplate transactions;
    private final AtomicLong blogIds = new AtomicLong(91_000L);

    @BeforeEach
    void setUp() {
        service = new BlogServiceImpl();
        ReflectionTestUtils.setField(service, "metrics",
                new LocalDealsMetrics(new SimpleMeterRegistry()));
        ReflectionTestUtils.setField(service, "baseMapper", blogMapper);
        ReflectionTestUtils.setField(service, "uploadFileService", uploadFileService);
        ReflectionTestUtils.setField(service, "followService", followService);
        ReflectionTestUtils.setField(service, "blogHotRankService", hotRankService);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redisTemplate);

        when(uploadFileService.validateTemporaryImages("", USER_ID))
                .thenReturn(Collections.emptyList());
        when(blogMapper.insert(any(Blog.class))).thenAnswer(invocation -> {
            Blog blog = invocation.getArgument(0);
            blog.setId(blogIds.incrementAndGet());
            return 1;
        });
        when(followService.query()).thenReturn(followQuery);
        when(followQuery.eq("follow_user_id", USER_ID)).thenReturn(followQuery);
        when(followQuery.list()).thenReturn(Collections.emptyList());

        TestTransactionManager transactionManager = new TestTransactionManager();
        transactionManager.setTransactionSynchronization(
                AbstractPlatformTransactionManager.SYNCHRONIZATION_ALWAYS);
        transactions = new TransactionTemplate(transactionManager);

        UserDTO user = new UserDTO();
        user.setId(USER_ID);
        UserHolder.saveUser(user);
    }

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
    }

    @Test
    void committedTransactionPublishesToHotRankOnlyAfterCommit() {
        Blog blog = newBlog();

        Result result = transactions.execute(status -> {
            Result pending = service.saveBlog(blog);
            assertThat(pending.getSuccess()).isTrue();
            verify(hotRankService, never()).addNewBlogAfterCommit(anyLong());
            return pending;
        });

        assertThat(result).isNotNull();
        verify(hotRankService).addNewBlogAfterCommit(blog.getId());
    }

    @Test
    void rolledBackTransactionNeverPublishesToHotRank() {
        Blog blog = newBlog();

        Result result = transactions.execute(status -> {
            Result pending = service.saveBlog(blog);
            assertThat(pending.getSuccess()).isTrue();
            status.setRollbackOnly();
            return pending;
        });

        assertThat(result).isNotNull();
        verify(hotRankService, never()).addNewBlogAfterCommit(anyLong());
    }

    private static Blog newBlog() {
        return new Blog()
                .setShopId(1L)
                .setTitle("transaction synchronization test")
                .setImages("")
                .setContent("publish only after commit");
    }

    /** No resource is needed; AbstractPlatformTransactionManager drives real Spring callbacks. */
    private static final class TestTransactionManager extends AbstractPlatformTransactionManager {

        private static final long serialVersionUID = 1L;

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            // No external resource: this test exercises transaction synchronization itself.
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            // AbstractPlatformTransactionManager triggers afterCommit after this method returns.
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            // A rollback deliberately has no resource side effect in this isolated test.
        }
    }
}

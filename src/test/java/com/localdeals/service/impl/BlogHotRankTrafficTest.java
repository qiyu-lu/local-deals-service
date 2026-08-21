package com.localdeals.service.impl;

import com.localdeals.config.BlogHotRankProperties;
import com.localdeals.config.TrafficControlProperties;
import com.localdeals.dto.Result;
import com.localdeals.entity.Blog;
import com.localdeals.mapper.BlogMapper;
import com.localdeals.observability.LocalDealsMetrics;
import com.localdeals.service.BlogHotRankReadResult;
import com.localdeals.service.BlogHotRankService;
import com.localdeals.service.BlogHotRankWarmupService;
import com.localdeals.service.LocalReadBulkhead;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BlogHotRankTrafficTest {
    @AfterEach
    void clearUser() {
        com.localdeals.utils.UserHolder.removeUser();
    }

    @Test
    void rankedDbHydrationStaysInsidePermitAndPreservesWholePageOrder() {
        BlogServiceImpl service = new BlogServiceImpl();
        BlogMapper mapper = mock(BlogMapper.class);
        BlogHotRankService rank = mock(BlogHotRankService.class);
        when(rank.readPage(1)).thenReturn(BlogHotRankReadResult.hit(Arrays.asList(19L, 2L)));
        Blog blog2 = new Blog();
        blog2.setId(2L);
        Blog blog19 = new Blog();
        blog19.setId(19L);
        when(mapper.selectBatchIds(anyCollection())).thenReturn(Arrays.asList(blog2, blog19));
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        ReflectionTestUtils.setField(service, "blogHotRankService", rank);
        ReflectionTestUtils.setField(service, "blogHotRankWarmupService",
                mock(BlogHotRankWarmupService.class));
        ReflectionTestUtils.setField(service, "blogHotRankProperties", new BlogHotRankProperties());
        ReflectionTestUtils.setField(service, "metrics",
                new LocalDealsMetrics(new SimpleMeterRegistry()));
        ReflectionTestUtils.setField(service, "localReadBulkhead", bulkhead());

        Result result = service.queryHotBlog(1);

        @SuppressWarnings("unchecked")
        List<Blog> blogs = (List<Blog>) result.getData();
        assertThat(blogs).extracting(Blog::getId).containsExactly(19L, 2L);
    }

    @Test
    void emptyReadyRankDoesNotConsumeDbPermitOrQueryMysql() {
        BlogServiceImpl service = new BlogServiceImpl();
        BlogMapper mapper = mock(BlogMapper.class);
        BlogHotRankService rank = mock(BlogHotRankService.class);
        when(rank.readPage(1)).thenReturn(BlogHotRankReadResult.hit(Collections.emptyList()));
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        ReflectionTestUtils.setField(service, "blogHotRankService", rank);

        Result result = service.queryHotBlog(1);

        assertThat((List<?>) result.getData()).isEmpty();
        verifyNoInteractions(mapper);
    }

    private static LocalReadBulkhead bulkhead() {
        TrafficControlProperties properties = new TrafficControlProperties();
        return new LocalReadBulkhead(properties,
                new LocalDealsMetrics(new SimpleMeterRegistry()));
    }
}

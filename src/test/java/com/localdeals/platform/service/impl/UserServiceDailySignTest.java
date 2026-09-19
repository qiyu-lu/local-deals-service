package com.localdeals.platform.service.impl;

import com.localdeals.platform.dto.Result;
import com.localdeals.platform.dto.UserDTO;
import com.localdeals.platform.entity.SignRecord;
import com.localdeals.platform.mapper.SignMapper;
import com.localdeals.platform.observability.LocalDealsMetrics;
import com.localdeals.platform.service.BusinessDateProvider;
import com.localdeals.platform.utils.UserHolder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class UserServiceDailySignTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 22);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final SignMapper signMapper = mock(SignMapper.class);
    private final UserServiceImpl service = new UserServiceImpl(redis,
            new LocalDealsMetrics(new SimpleMeterRegistry()), signMapper,
            new BusinessDateProvider(Clock.fixed(Instant.parse("2026-08-21T16:00:00Z"),
                    ZoneId.of("Asia/Shanghai")), ZoneId.of("Asia/Shanghai")));

    @AfterEach
    void clearUser() {
        UserHolder.removeUser();
    }

    @Test
    void duplicateSignIsSuccessfulAndDoesNotUseRedis() {
        UserDTO user = new UserDTO();
        user.setId(7L);
        UserHolder.saveUser(user);
        doThrow(new DuplicateKeyException("same user/date"))
                .when(signMapper).insert(any(SignRecord.class));

        Result result = service.sign();

        assertThat(result.getSuccess()).isTrue();
        verify(signMapper).insert(any(SignRecord.class));
        verifyNoInteractions(redis);
    }

    @Test
    void streakCountsOnlyConsecutiveMysqlDatesEndingToday() {
        UserDTO user = new UserDTO();
        user.setId(7L);
        UserHolder.saveUser(user);
        when(signMapper.selectDatesUntil(7L, TODAY)).thenReturn(Arrays.asList(
                TODAY, TODAY.minusDays(1), TODAY.minusDays(3)));

        Result result = service.signCount();

        assertThat(result.getData()).isEqualTo(2);
        verify(signMapper).selectDatesUntil(7L, TODAY);
    }
}

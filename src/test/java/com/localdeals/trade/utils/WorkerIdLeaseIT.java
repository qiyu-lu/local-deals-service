package com.localdeals.trade.utils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import jakarta.annotation.Resource;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = RedisAutoConfiguration.class)
@ActiveProfiles("test")
class WorkerIdLeaseIT {

    @Resource
    private StringRedisTemplate redis;

    private final List<WorkerIdLease> leases = new ArrayList<>();

    @BeforeEach
    @AfterEach
    void releaseAll() {
        leases.forEach(WorkerIdLease::release);
        leases.clear();
        for (int id = 0; id < WorkerIdLease.MAX_WORKERS; id++) {
            redis.delete(WorkerIdLease.KEY_PREFIX + id);
        }
    }

    private WorkerIdLease newLease() {
        WorkerIdLease lease = new WorkerIdLease(redis, Duration.ofSeconds(30), System::currentTimeMillis);
        leases.add(lease);
        lease.maintain();
        return lease;
    }

    @Test
    void everyInstanceHoldsADifferentWorkerIdUntilAllSlotsAreTaken() {
        Set<Integer> ids = new HashSet<>();
        for (int i = 0; i < WorkerIdLease.MAX_WORKERS; i++) {
            ids.add(newLease().workerId());
        }
        assertThat(ids).hasSize(WorkerIdLease.MAX_WORKERS);

        WorkerIdLease overflow = newLease();
        assertThatThrownBy(overflow::workerId).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void releaseFreesOnlyTheOwnSlot() {
        WorkerIdLease first = newLease();
        int held = first.workerId();
        redis.opsForValue().set(WorkerIdLease.KEY_PREFIX + held, "someone-else");

        first.release();
        assertThat(redis.opsForValue().get(WorkerIdLease.KEY_PREFIX + held)).isEqualTo("someone-else");

        redis.delete(WorkerIdLease.KEY_PREFIX + held);
        WorkerIdLease second = newLease();
        second.maintain();
        assertThat(second.workerId()).isBetween(0, WorkerIdLease.MAX_WORKERS - 1);
    }

    @Test
    void aSlotTakenOverByAnotherOwnerIsDroppedOnRenewal() {
        WorkerIdLease lease = newLease();
        int held = lease.workerId();
        redis.opsForValue().set(WorkerIdLease.KEY_PREFIX + held, "new-owner", Duration.ofSeconds(30));

        lease.maintain();

        assertThat(lease.workerId()).isNotEqualTo(held);
        assertThat(redis.opsForValue().get(WorkerIdLease.KEY_PREFIX + held)).isEqualTo("new-owner");
    }
}

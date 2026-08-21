package com.localdeals.service;

import com.localdeals.config.TrafficControlProperties;
import com.localdeals.exception.ApiErrorCodes;
import com.localdeals.exception.ApiStatusException;
import com.localdeals.observability.LocalDealsMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/** Two deliberately local, non-queuing concurrency compartments for selected read paths. */
@Component
@Slf4j
public class LocalReadBulkhead {
    public enum Resource { DB_READ, SEARCH }

    private final Compartment dbRead;
    private final Compartment search;
    private final LocalDealsMetrics metrics;

    public LocalReadBulkhead(TrafficControlProperties properties, LocalDealsMetrics metrics) {
        this.metrics = metrics;
        this.dbRead = new Compartment(properties.getRead().getDbMaxConcurrent(),
                properties.getRead().getDbMaxWait());
        this.search = new Compartment(properties.getRead().getSearchMaxConcurrent(),
                properties.getRead().getSearchMaxWait());
    }

    public <T> T executeDbRead(Supplier<T> callback) {
        return execute(Resource.DB_READ, callback);
    }

    public <T> T executeSearch(Supplier<T> callback) {
        return execute(Resource.SEARCH, callback);
    }

    private <T> T execute(Resource resource, Supplier<T> callback) {
        if (callback == null) {
            throw new IllegalArgumentException("Read callback is required");
        }
        Compartment compartment = resource == Resource.DB_READ ? dbRead : search;
        LocalDealsMetrics.TrafficResource metricResource = metricResource(resource);
        final boolean acquired;
        try {
            acquired = compartment.permits.tryAcquire(
                    compartment.maxWait.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            metrics.recordTraffic(metricResource, LocalDealsMetrics.TrafficResult.UNAVAILABLE,
                    LocalDealsMetrics.TrafficReason.INTERRUPTED);
            throw unavailable(resource);
        }
        if (!acquired) {
            metrics.recordTraffic(metricResource, LocalDealsMetrics.TrafficResult.REJECTED,
                    LocalDealsMetrics.TrafficReason.CONCURRENCY);
            throw overloaded(resource);
        }

        int current = compartment.inflight.incrementAndGet();
        metrics.setTrafficInflight(metricResource, current);
        metrics.recordTraffic(metricResource, LocalDealsMetrics.TrafficResult.ALLOWED,
                LocalDealsMetrics.TrafficReason.NONE);
        try {
            return callback.get();
        } catch (ApiStatusException known) {
            throw known;
        } catch (RuntimeException dependencyFailure) {
            log.warn("Bounded read dependency failed. resource={}", resource, dependencyFailure);
            throw unavailable(resource);
        } finally {
            int remaining = compartment.inflight.decrementAndGet();
            metrics.setTrafficInflight(metricResource, remaining);
            compartment.permits.release();
        }
    }

    private static ApiStatusException overloaded(Resource resource) {
        boolean database = resource == Resource.DB_READ;
        return new ApiStatusException(HttpStatus.TOO_MANY_REQUESTS,
                database ? ApiErrorCodes.READ_OVERLOADED : ApiErrorCodes.SEARCH_OVERLOADED,
                database ? "读请求过多，请稍后重试" : "搜索请求过多，请稍后重试");
    }

    private static ApiStatusException unavailable(Resource resource) {
        boolean database = resource == Resource.DB_READ;
        return new ApiStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                database ? ApiErrorCodes.DATABASE_UNAVAILABLE : ApiErrorCodes.SEARCH_UNAVAILABLE,
                database ? "数据暂不可用，请稍后重试" : "搜索暂不可用，请稍后重试");
    }

    private static LocalDealsMetrics.TrafficResource metricResource(Resource resource) {
        return resource == Resource.DB_READ
                ? LocalDealsMetrics.TrafficResource.DB_READ
                : LocalDealsMetrics.TrafficResource.SEARCH;
    }

    private static final class Compartment {
        private final Semaphore permits;
        private final Duration maxWait;
        private final AtomicInteger inflight = new AtomicInteger();

        private Compartment(int maxConcurrent, Duration maxWait) {
            this.permits = new Semaphore(maxConcurrent, false);
            this.maxWait = maxWait;
        }
    }
}

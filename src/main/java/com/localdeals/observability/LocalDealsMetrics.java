package com.localdeals.observability;

import com.localdeals.service.BlogHotRankReadResult;
import com.localdeals.service.BlogHotRankService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Pre-registers the finite M5A metric vocabulary and exposes typed recording methods.
 * Arbitrary strings never become tags.
 */
@Component
public class LocalDealsMetrics {

    public enum LikeOperation { LIKE, UNLIKE }
    public enum LikeResult { CHANGED, UNCHANGED, NOT_FOUND, FAILURE }
    public enum OutboxResult { SUCCESS, EMPTY, LOCK_BUSY, DB_ERROR, REDIS_LOCK_ERROR }
    public enum CacheResource { SHOP_DETAIL, SHOP_TYPE }
    public enum CacheResult {
        HIT, EMPTY_HIT, MISS, BAD_VALUE, REDIS_ERROR, DB_SUCCESS, DB_EMPTY, DB_ERROR
    }
    public enum CacheSingleFlightResult { LEADER, SHARED, SHARED_TIMEOUT }
    public enum CacheMaintenanceOperation { WRITE, EVICT }
    public enum CacheMaintenanceResult { SUCCESS, FAILURE, SKIPPED }
    public enum AuthFlow { OTP_SEND, USER_LOGIN, ADMIN_LOGIN }
    public enum AuthResult { SUCCESS, INVALID_INPUT, REJECTED, LOCKED, UNAVAILABLE, FAILURE }
    public enum EsTable { SHOP, BLOG, IGNORED }
    public enum EsOperation { INSERT, UPDATE, DELETE, OTHER }
    public enum EsMessageResult { SUCCESS, PARTIAL_FAILURE, FAILURE, IGNORED }
    public enum EsRowResult { SUCCESS, FAILURE, IGNORED }
    public enum CollectorResult { SUCCESS, FAILURE }
    public enum TrafficResource { SECKILL, DB_READ, SEARCH }
    public enum TrafficResult { ALLOWED, REJECTED, UNAVAILABLE }
    public enum TrafficReason { NONE, ACTIVITY, USER, IP, CONCURRENCY, REDIS, INTERRUPTED }
    public enum SeckillDbPersistResult { SUCCESS, FAILURE }
    public enum MqConsumeOutcome {
        PERSISTED,
        ALREADY_SUCCESS,
        ALREADY_FAILED,
        MALFORMED,
        LOCK_BUSY,
        RESERVATION_MISMATCH,
        STATE_MISSING,
        COMPENSATED,
        QUARANTINED,
        TRANSIENT_ERROR,
        COMPENSATION_ERROR,
        QUARANTINE_ERROR
    }

    private final Map<String, Counter> likeCommands = new HashMap<>();
    private final Map<OutboxResult, Counter> outboxBatches = new EnumMap<>(OutboxResult.class);
    private final Timer outboxDuration;
    private final DistributionSummary outboxEvents;
    private final Map<String, Counter> hotRankReads = new HashMap<>();
    private final Map<BlogHotRankService.RebuildOutcome, Counter> hotRankRebuilds =
            new EnumMap<>(BlogHotRankService.RebuildOutcome.class);
    private final Timer hotRankRebuildDuration;
    private final Timer hotRankDbFallback;
    private final Map<String, Counter> cacheAccess = new HashMap<>();
    private final Map<CacheResource, Timer> cacheDbFallback = new EnumMap<>(CacheResource.class);
    private final Map<String, Counter> cacheSingleFlight = new HashMap<>();
    private final Map<String, Counter> cacheMaintenance = new HashMap<>();
    private final Map<String, Counter> authRequests = new HashMap<>();
    private final Map<String, Counter> esMessages = new HashMap<>();
    private final Map<String, Counter> esRows = new HashMap<>();
    private final Map<String, Timer> esDurations = new HashMap<>();
    private final Map<CollectorResult, Counter> outboxCollectors =
            new EnumMap<>(CollectorResult.class);
    private final Map<CollectorResult, Counter> hotRankCollectors =
            new EnumMap<>(CollectorResult.class);
    private final Map<CollectorResult, Counter> seckillCollectors =
            new EnumMap<>(CollectorResult.class);
    private final Map<MqConsumeOutcome, Counter> mqConsumeOutcomes =
            new EnumMap<>(MqConsumeOutcome.class);
    private final Map<SeckillDbPersistResult, Timer> seckillDbPersistDurations =
            new EnumMap<>(SeckillDbPersistResult.class);
    private final Map<String, Counter> trafficDecisions = new HashMap<>();
    private final Map<TrafficResource, AtomicInteger> trafficInflight =
            new EnumMap<>(TrafficResource.class);

    private final AtomicReference<Double> outboxPending = nanGauge();
    private final AtomicReference<Double> outboxOldestAge = nanGauge();
    private final AtomicReference<Double> hotRankAge = nanGauge();
    private final AtomicReference<Double> seckillDue = nanGauge();
    private final AtomicReference<Double> seckillOldestOverdue = nanGauge();
    private final AtomicReference<Double> seckillQuarantine = nanGauge();

    public LocalDealsMetrics(MeterRegistry registry) {
        for (LikeOperation operation : LikeOperation.values()) {
            for (LikeResult result : LikeResult.values()) {
                likeCommands.put(key(operation, result), Counter.builder("local_deals.blog.like.command")
                        .description("Durable desired-state blog-like command outcomes")
                        .tag("operation", metricValue(operation))
                        .tag("result", metricValue(result))
                        .register(registry));
            }
        }
        for (OutboxResult result : OutboxResult.values()) {
            outboxBatches.put(result, Counter.builder("local_deals.blog.like.outbox.batch")
                    .description("Transactional blog-like outbox batch outcomes")
                    .tag("result", metricValue(result))
                    .register(registry));
        }
        outboxDuration = histogramTimer(registry, "local_deals.blog.like.outbox.duration",
                "Transactional blog-like outbox batch duration");
        outboxEvents = DistributionSummary.builder("local_deals.blog.like.outbox.events")
                .description("Committed events per blog-like outbox batch")
                .baseUnit("events")
                .register(registry);

        hotRankReads.put("hit", counter(registry, "local_deals.blog.hot_rank.read",
                "Blog hot-rank read outcomes", "result", "hit"));
        for (BlogHotRankReadResult.MissReason reason : BlogHotRankReadResult.MissReason.values()) {
            hotRankReads.put(metricValue(reason), counter(registry,
                    "local_deals.blog.hot_rank.read", "Blog hot-rank read outcomes",
                    "result", metricValue(reason)));
        }
        for (BlogHotRankService.RebuildOutcome outcome : BlogHotRankService.RebuildOutcome.values()) {
            hotRankRebuilds.put(outcome, counter(registry, "local_deals.blog.hot_rank.rebuild",
                    "Blog hot-rank rebuild outcomes", "result", rebuildValue(outcome)));
        }
        hotRankRebuildDuration = histogramTimer(registry,
                "local_deals.blog.hot_rank.rebuild.duration", "Blog hot-rank rebuild duration");
        hotRankDbFallback = histogramTimer(registry,
                "local_deals.blog.hot_rank.db_fallback", "Blog hot-rank MySQL fallback duration");

        for (CacheResource resource : CacheResource.values()) {
            for (CacheResult result : CacheResult.values()) {
                cacheAccess.put(key(resource, result), Counter.builder("local_deals.cache.access")
                        .description("Cache and fallback outcomes at bounded resource boundaries")
                        .tag("resource", metricValue(resource))
                        .tag("result", metricValue(result))
                        .register(registry));
            }
            cacheDbFallback.put(resource, Timer.builder("local_deals.cache.db_fallback")
                    .description("Database fallback duration after a cache miss, bad value, or Redis error")
                    .tag("resource", metricValue(resource))
                    .publishPercentileHistogram()
                    .register(registry));
            for (CacheSingleFlightResult result : CacheSingleFlightResult.values()) {
                cacheSingleFlight.put(key(resource, result),
                        Counter.builder("local_deals.cache.singleflight")
                                .description("Per-JVM cache fallback coalescing outcomes")
                                .tag("resource", metricValue(resource))
                                .tag("result", metricValue(result))
                                .register(registry));
            }
            for (CacheMaintenanceOperation operation : CacheMaintenanceOperation.values()) {
                for (CacheMaintenanceResult result : CacheMaintenanceResult.values()) {
                    cacheMaintenance.put(key(resource, operation, result),
                            Counter.builder("local_deals.cache.maintenance")
                                    .description("Best-effort cache write and eviction outcomes")
                                    .tags("resource", metricValue(resource),
                                            "operation", metricValue(operation),
                                            "result", metricValue(result))
                                    .register(registry));
                }
            }
        }

        for (AuthFlow flow : AuthFlow.values()) {
            for (AuthResult result : AuthResult.values()) {
                authRequests.put(key(flow, result), Counter.builder("local_deals.auth.request")
                        .description("Authentication entry-point outcomes without identity tags")
                        .tag("flow", metricValue(flow))
                        .tag("result", metricValue(result))
                        .register(registry));
            }
        }

        for (EsTable table : EsTable.values()) {
            for (EsOperation operation : EsOperation.values()) {
                for (EsMessageResult result : EsMessageResult.values()) {
                    esMessages.put(key(table, operation, result),
                            Counter.builder("local_deals.es.sync.messages")
                                    .description("Canal messages observed by the ES consumer")
                                    .tags("table", metricValue(table),
                                            "operation", metricValue(operation),
                                            "result", metricValue(result))
                                    .register(registry));
                }
                for (EsRowResult result : EsRowResult.values()) {
                    esRows.put(key(table, operation, result),
                            Counter.builder("local_deals.es.sync.rows")
                                    .description("Canal rows applied or rejected by the ES consumer")
                                    .tags("table", metricValue(table),
                                            "operation", metricValue(operation),
                                            "result", metricValue(result))
                                    .register(registry));
                }
                if (table != EsTable.IGNORED) {
                    esDurations.put(key(table, operation),
                            Timer.builder("local_deals.es.sync.apply.duration")
                                    .description("Elasticsearch application duration per target message")
                                    .tags("table", metricValue(table),
                                            "operation", metricValue(operation))
                                    .publishPercentileHistogram()
                                    .register(registry));
                }
            }
        }

        registerCollectorCounters(registry, "local_deals.blog.like.outbox.collector",
                "Blog-like outbox backlog collector outcomes", outboxCollectors);
        registerCollectorCounters(registry, "local_deals.blog.hot_rank.collector",
                "Blog hot-rank metadata collector outcomes", hotRankCollectors);
        registerCollectorCounters(registry, "local_deals.seckill.processing.collector",
                "Seckill processing backlog collector outcomes", seckillCollectors);
        gauge(registry, "local_deals.blog.like.outbox.pending", "events",
                "Pending blog-like outbox events", outboxPending);
        gauge(registry, "local_deals.blog.like.outbox.oldest_age", "seconds",
                "Oldest pending blog-like outbox event age", outboxOldestAge);
        gauge(registry, "local_deals.blog.hot_rank.age", "seconds",
                "Age of the last valid published blog hot rank", hotRankAge);
        gauge(registry, "local_deals.seckill.processing.due", "orders",
                "Seckill reservations currently due for reconciliation", seckillDue);
        gauge(registry, "local_deals.seckill.processing.oldest_overdue", "seconds",
                "Age beyond due time of the oldest due seckill reservation", seckillOldestOverdue);
        gauge(registry, "local_deals.seckill.processing.quarantine", "orders",
                "Seckill reservations in reconciliation quarantine", seckillQuarantine);

        for (MqConsumeOutcome outcome : MqConsumeOutcome.values()) {
            mqConsumeOutcomes.put(outcome,
                    counter(registry, "local_deals.seckill.mq.consume.outcome",
                            "Detailed finite outcome for each seckill MQ delivery attempt",
                            "result", metricValue(outcome)));
        }
        for (SeckillDbPersistResult result : SeckillDbPersistResult.values()) {
            seckillDbPersistDurations.put(result,
                    Timer.builder("local_deals.seckill.db.persist.duration")
                            .description("MySQL persistence duration for an admitted seckill order")
                            .tag("result", metricValue(result))
                            .publishPercentileHistogram()
                            .register(registry));
        }
        registerTrafficDecision(registry, TrafficResource.SECKILL,
                TrafficResult.ALLOWED, TrafficReason.NONE);
        registerTrafficDecision(registry, TrafficResource.SECKILL,
                TrafficResult.REJECTED, TrafficReason.ACTIVITY);
        registerTrafficDecision(registry, TrafficResource.SECKILL,
                TrafficResult.REJECTED, TrafficReason.USER);
        registerTrafficDecision(registry, TrafficResource.SECKILL,
                TrafficResult.REJECTED, TrafficReason.IP);
        registerTrafficDecision(registry, TrafficResource.SECKILL,
                TrafficResult.UNAVAILABLE, TrafficReason.REDIS);
        registerLocalTrafficResource(registry, TrafficResource.DB_READ);
        registerLocalTrafficResource(registry, TrafficResource.SEARCH);
    }

    public void recordLike(LikeOperation operation, LikeResult result) {
        safeIncrement(likeCommands.get(key(operation, result)));
    }

    public void recordOutbox(OutboxResult result) {
        safeIncrement(outboxBatches.get(result));
    }

    public void recordOutboxDuration(long nanos) {
        safeRecord(outboxDuration, nanos);
    }

    public void recordOutboxEvents(int count) {
        try {
            outboxEvents.record(count);
        } catch (RuntimeException ignored) {
            // Metrics must not affect an already committed outbox batch.
        }
    }

    public void recordHotRankRead(BlogHotRankReadResult result) {
        safeIncrement(hotRankReads.get(result.isHit() ? "hit" : metricValue(result.getMissReason())));
    }

    public void recordHotRankRebuild(BlogHotRankService.RebuildOutcome outcome, long nanos) {
        safeIncrement(hotRankRebuilds.get(outcome));
        safeRecord(hotRankRebuildDuration, nanos);
    }

    public void recordHotRankDbFallback(long nanos) {
        safeRecord(hotRankDbFallback, nanos);
    }

    public void recordCache(CacheResource resource, CacheResult result) {
        safeIncrement(cacheAccess.get(key(resource, result)));
    }

    public void recordCacheDbFallback(CacheResource resource, long nanos) {
        safeRecord(cacheDbFallback.get(resource), nanos);
    }

    public void recordCacheSingleFlight(CacheResource resource, CacheSingleFlightResult result) {
        safeIncrement(cacheSingleFlight.get(key(resource, result)));
    }

    public void recordCacheMaintenance(CacheResource resource,
                                       CacheMaintenanceOperation operation,
                                       CacheMaintenanceResult result) {
        safeIncrement(cacheMaintenance.get(key(resource, operation, result)));
    }

    public void recordAuth(AuthFlow flow, AuthResult result) {
        safeIncrement(authRequests.get(key(flow, result)));
    }

    public void recordEsMessage(EsTable table, EsOperation operation, EsMessageResult result) {
        safeIncrement(esMessages.get(key(table, operation, result)));
    }

    public void recordEsRow(EsTable table, EsOperation operation, EsRowResult result) {
        safeIncrement(esRows.get(key(table, operation, result)));
    }

    public void recordEsDuration(EsTable table, EsOperation operation, long nanos) {
        safeRecord(esDurations.get(key(table, operation)), nanos);
    }

    public void recordMqConsumeOutcome(MqConsumeOutcome outcome) {
        safeIncrement(mqConsumeOutcomes.get(outcome));
    }

    public void recordSeckillDbPersist(SeckillDbPersistResult result, long nanos) {
        safeRecord(seckillDbPersistDurations.get(result), nanos);
    }

    public void recordTraffic(TrafficResource resource, TrafficResult result, TrafficReason reason) {
        safeIncrement(trafficDecisions.get(key(resource, result, reason)));
    }

    public void setTrafficInflight(TrafficResource resource, int value) {
        AtomicInteger gauge = trafficInflight.get(resource);
        if (gauge != null) {
            gauge.set(Math.max(0, value));
        }
    }

    public void updateOutboxBacklog(long pending, double oldestAgeSeconds) {
        outboxPending.set((double) pending);
        outboxOldestAge.set(oldestAgeSeconds);
        safeIncrement(outboxCollectors.get(CollectorResult.SUCCESS));
    }

    public void failOutboxCollector() {
        outboxPending.set(Double.NaN);
        outboxOldestAge.set(Double.NaN);
        safeIncrement(outboxCollectors.get(CollectorResult.FAILURE));
    }

    public void updateHotRankAge(double ageSeconds) {
        hotRankAge.set(ageSeconds);
        safeIncrement(hotRankCollectors.get(CollectorResult.SUCCESS));
    }

    public void failHotRankCollector() {
        hotRankAge.set(Double.NaN);
        safeIncrement(hotRankCollectors.get(CollectorResult.FAILURE));
    }

    public void updateSeckillBacklog(long due, double oldestOverdueSeconds, long quarantine) {
        seckillDue.set((double) due);
        seckillOldestOverdue.set(oldestOverdueSeconds);
        seckillQuarantine.set((double) quarantine);
        safeIncrement(seckillCollectors.get(CollectorResult.SUCCESS));
    }

    public void failSeckillCollector() {
        seckillDue.set(Double.NaN);
        seckillOldestOverdue.set(Double.NaN);
        seckillQuarantine.set(Double.NaN);
        safeIncrement(seckillCollectors.get(CollectorResult.FAILURE));
    }

    private static Counter counter(MeterRegistry registry, String name, String description,
                                   String tagName, String tagValue) {
        return Counter.builder(name).description(description).tag(tagName, tagValue).register(registry);
    }

    private static Timer histogramTimer(MeterRegistry registry, String name, String description) {
        return Timer.builder(name).description(description).publishPercentileHistogram().register(registry);
    }

    private static void registerCollectorCounters(MeterRegistry registry, String name,
                                                   String description,
                                                   Map<CollectorResult, Counter> target) {
        for (CollectorResult result : CollectorResult.values()) {
            target.put(result, counter(registry, name, description, "result", metricValue(result)));
        }
    }

    private void registerLocalTrafficResource(MeterRegistry registry, TrafficResource resource) {
        registerTrafficDecision(registry, resource, TrafficResult.ALLOWED, TrafficReason.NONE);
        registerTrafficDecision(registry, resource, TrafficResult.REJECTED, TrafficReason.CONCURRENCY);
        registerTrafficDecision(registry, resource, TrafficResult.UNAVAILABLE, TrafficReason.INTERRUPTED);
        AtomicInteger inflight = new AtomicInteger();
        trafficInflight.put(resource, inflight);
        Gauge.builder("local_deals.traffic.inflight", inflight, AtomicInteger::get)
                .description("Current permits held by bounded local read resources")
                .tag("resource", metricValue(resource))
                .register(registry);
    }

    private void registerTrafficDecision(MeterRegistry registry, TrafficResource resource,
                                         TrafficResult result, TrafficReason reason) {
        trafficDecisions.put(key(resource, result, reason),
                Counter.builder("local_deals.traffic.decision")
                        .description("Finite resource admission decisions")
                        .tags("resource", metricValue(resource),
                                "result", metricValue(result),
                                "reason", metricValue(reason))
                        .register(registry));
    }

    private static void gauge(MeterRegistry registry, String name, String baseUnit,
                              String description, AtomicReference<Double> value) {
        Gauge.builder(name, value, AtomicReference::get)
                .description(description)
                .baseUnit(baseUnit)
                .register(registry);
    }

    private static AtomicReference<Double> nanGauge() {
        return new AtomicReference<>(Double.NaN);
    }

    private static void safeIncrement(Counter counter) {
        if (counter == null) {
            return;
        }
        try {
            counter.increment();
        } catch (RuntimeException ignored) {
            // Observability cannot change a business result.
        }
    }

    private static void safeRecord(Timer timer, long nanos) {
        if (timer == null) {
            return;
        }
        try {
            timer.record(Math.max(0L, nanos), TimeUnit.NANOSECONDS);
        } catch (RuntimeException ignored) {
            // Observability cannot change a business result.
        }
    }

    private static String rebuildValue(BlogHotRankService.RebuildOutcome outcome) {
        return outcome == BlogHotRankService.RebuildOutcome.SKIPPED_LOCK_BUSY
                ? "lock_busy" : metricValue(outcome);
    }

    private static String metricValue(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    private static String key(Enum<?>... values) {
        StringBuilder key = new StringBuilder();
        for (Enum<?> value : values) {
            if (key.length() > 0) {
                key.append('|');
            }
            key.append(value.name());
        }
        return key.toString();
    }
}

package com.localdeals.utils;

import com.localdeals.observability.LocalDealsMetrics;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

/**
 * Coalesces overlapping loads for the same cache resource and key inside one JVM.
 * The first request remains the leader and executes its callback on the request thread.
 */
@Component
public class SingleFlightLoader {

    private final ConcurrentMap<FlightKey, FutureTask<Object>> inFlight = new ConcurrentHashMap<>();
    private final LocalDealsMetrics metrics;

    public SingleFlightLoader(LocalDealsMetrics metrics) {
        this.metrics = metrics;
    }

    @SuppressWarnings("unchecked")
    public <T> T load(LocalDealsMetrics.CacheResource resource, String key, Callable<T> callback) {
        Objects.requireNonNull(resource, "resource");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(callback, "callback");

        FlightKey flightKey = new FlightKey(resource, key);
        FutureTask<Object> candidate = new FutureTask<>(() -> callback.call());
        FutureTask<Object> existing = inFlight.putIfAbsent(flightKey, candidate);
        boolean leader = existing == null;
        FutureTask<Object> task = leader ? candidate : existing;
        metrics.recordCacheSingleFlight(resource, leader
                ? LocalDealsMetrics.CacheSingleFlightResult.LEADER
                : LocalDealsMetrics.CacheSingleFlightResult.SHARED);

        if (leader) {
            task.run();
        }
        try {
            return (T) task.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new SingleFlightInterruptedException(interrupted);
        } catch (ExecutionException failed) {
            throw propagate(failed.getCause());
        } finally {
            if (leader) {
                inFlight.remove(flightKey, task);
            }
        }
    }

    int inFlightCount() {
        return inFlight.size();
    }

    private static RuntimeException propagate(Throwable failure) {
        if (failure instanceof RuntimeException) {
            return (RuntimeException) failure;
        }
        if (failure instanceof Error) {
            throw (Error) failure;
        }
        return new IllegalStateException("Singleflight load failed", failure);
    }

    private static final class FlightKey {
        private final LocalDealsMetrics.CacheResource resource;
        private final String key;

        private FlightKey(LocalDealsMetrics.CacheResource resource, String key) {
            this.resource = resource;
            this.key = key;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof FlightKey)) {
                return false;
            }
            FlightKey that = (FlightKey) other;
            return resource == that.resource && key.equals(that.key);
        }

        @Override
        public int hashCode() {
            return 31 * resource.hashCode() + key.hashCode();
        }
    }

    public static final class SingleFlightInterruptedException extends RuntimeException {
        private SingleFlightInterruptedException(InterruptedException cause) {
            super("Interrupted while waiting for a shared cache load", cause);
        }
    }
}

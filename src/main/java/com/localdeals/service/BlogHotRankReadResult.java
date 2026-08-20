package com.localdeals.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Explicit cache-read result. Callers must fall back to MySQL for every miss.
 */
public final class BlogHotRankReadResult {

    public enum MissReason {
        READ_DISABLED,
        INVALID_PAGE,
        OUTSIDE_TOP_K,
        NOT_READY,
        REDIS_UNAVAILABLE,
        BAD_METADATA,
        BAD_MEMBER,
        STALE,
        INCONSISTENT_SNAPSHOT
    }

    private final boolean hit;
    private final List<Long> blogIds;
    private final MissReason missReason;

    private BlogHotRankReadResult(boolean hit, List<Long> blogIds, MissReason missReason) {
        this.hit = hit;
        this.blogIds = Collections.unmodifiableList(new ArrayList<>(blogIds));
        this.missReason = missReason;
    }

    public static BlogHotRankReadResult hit(List<Long> blogIds) {
        if (blogIds == null) {
            throw new IllegalArgumentException("blogIds must not be null");
        }
        return new BlogHotRankReadResult(true, blogIds, null);
    }

    public static BlogHotRankReadResult miss(MissReason reason) {
        if (reason == null) {
            throw new IllegalArgumentException("miss reason must not be null");
        }
        return new BlogHotRankReadResult(false, Collections.emptyList(), reason);
    }

    public boolean isHit() {
        return hit;
    }

    public List<Long> getBlogIds() {
        return blogIds;
    }

    public MissReason getMissReason() {
        return missReason;
    }
}

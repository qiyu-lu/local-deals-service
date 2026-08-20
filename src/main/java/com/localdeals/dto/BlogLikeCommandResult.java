package com.localdeals.dto;

/**
 * Result of setting one user's durable like state for a blog.
 *
 * <p>The aggregate count is intentionally absent: it is maintained asynchronously from the
 * transactional outbox and must not be guessed on the request path.</p>
 */
public final class BlogLikeCommandResult {

    public enum Outcome {
        CHANGED,
        UNCHANGED,
        NOT_FOUND
    }

    private final boolean desired;
    private final boolean changed;
    private final Outcome outcome;

    private BlogLikeCommandResult(boolean desired, boolean changed, Outcome outcome) {
        this.desired = desired;
        this.changed = changed;
        this.outcome = outcome;
    }

    public static BlogLikeCommandResult changed(boolean desired) {
        return new BlogLikeCommandResult(desired, true, Outcome.CHANGED);
    }

    public static BlogLikeCommandResult unchanged(boolean desired) {
        return new BlogLikeCommandResult(desired, false, Outcome.UNCHANGED);
    }

    public static BlogLikeCommandResult notFound(boolean desired) {
        return new BlogLikeCommandResult(desired, false, Outcome.NOT_FOUND);
    }

    public boolean isDesired() {
        return desired;
    }

    public boolean isChanged() {
        return changed;
    }

    public Outcome getOutcome() {
        return outcome;
    }
}

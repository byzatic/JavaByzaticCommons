package io.github.byzatic.commons.token_bucket_limiter;

/**
 * A lightweight, thread-safe token-bucket rate limiter.
 * <p>
 * Refills at the configured {@code ratePerSecond} and allows bursts up to
 * {@code max(1, ratePerSecond)} tokens (bucket capacity). Uses {@code System.nanoTime()}
 * for time measurement and a floating-point token counter for smooth refill.
 *
 * <h3>Algorithm</h3>
 * <ul>
 *   <li>On each {@link #tryAcquire()} call, computes the elapsed time since the previous call
 *       and refills the internal token counter by {@code elapsedSeconds * ratePerSecond},
 *       clamped to {@code capacity}.</li>
 *   <li>If at least one token is available, consumes one and returns {@code true};
 *       otherwise returns {@code false} immediately.</li>
 * </ul>
 *
 * <h3>Burst behavior</h3>
 * <ul>
 *   <li>Bucket capacity (burst) defaults to {@code max(1, ratePerSecond)}:
 *     <ul>
 *       <li>If {@code ratePerSecond < 1}, the capacity is {@code 1} (no more than one event per burst).</li>
 *       <li>If {@code ratePerSecond >= 1}, an instantaneous burst of roughly {@code ratePerSecond} events
 *           is allowed, after which the throughput stabilizes at the configured rate.</li>
 *     </ul>
 *   </li>
 *   <li>If you need a different bucket size, add an alternative constructor with a {@code burstCapacity} parameter.</li>
 * </ul>
 *
 * <h3>Thread-safety</h3>
 * <ul>
 *   <li>Methods are thread-safe; internal state is guarded by synchronization.</li>
 * </ul>
 *
 * <h3>Complexity</h3>
 * <ul>
 *   <li>O(1) per call, with no allocations on the hot path.</li>
 * </ul>
 *
 * <h3>Example</h3>
 * <pre>{@code
 * Limiter limiter = new SimpleTokenBucketLimiter(5.0); // ~5 permits/sec, burst ~5
 * if (limiter.tryAcquire()) {
 *     // do work
 * } else {
 *     // skip or reschedule
 * }
 * }</pre>
 *
 * <h3>Typical placements</h3>
 * <ul>
 *   <li>Global limit: cap the total flow of all events.</li>
 *   <li>Per-path: tame particularly noisy files/paths.</li>
 *   <li>Pattern-based (via PathMatcher): e.g., for all {@code **//*.log} files.</li>
 * </ul>
*/
public final class SimpleTokenBucketLimiter implements Limiter {
    private final double ratePerSecond;
    private final double maxBurst;
    private double tokens;
    private long lastNanos;

    /**
     * @param ratePerSecond average permits per second, must be &gt; 0
     */
    public SimpleTokenBucketLimiter(double ratePerSecond) {
        if (ratePerSecond <= 0) {
            throw new IllegalArgumentException("ratePerSecond must be > 0");
        }
        this.ratePerSecond = ratePerSecond;
        this.maxBurst = Math.max(1.0, ratePerSecond);
        this.tokens = this.maxBurst;
        this.lastNanos = System.nanoTime();
    }


    /** {@inheritDoc} */
    @Override
    public synchronized boolean tryAcquire() {
        long now = System.nanoTime();
        double elapsedSec = (now - lastNanos) / 1_000_000_000.0;
        lastNanos = now;

        tokens = Math.min(maxBurst, tokens + elapsedSec * ratePerSecond);
        if (tokens >= 1.0) {
            tokens -= 1.0;
            return true;
        }
        return false;
    }
}

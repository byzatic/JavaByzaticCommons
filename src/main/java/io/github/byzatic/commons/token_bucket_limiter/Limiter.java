package io.github.byzatic.commons.token_bucket_limiter;


/**
 * Minimal, non-blocking rate limiter contract.
 * <p>
 * A call to {@link #tryAcquire()} attempts to consume a single permit and
 * returns immediately. Implementations should be thread-safe unless
 * explicitly stated otherwise.
 *
 * <h3>Semantics</h3>
 * <ul>
 *   <li>Non-blocking: never sleeps or waits.</li>
 *   <li>Unary: each call is for exactly one permit.</li>
 *   <li>Fairness is not guaranteed unless specified by the implementation.</li>
 * </ul>
 */
public interface Limiter {
    /**
     * Attempts to acquire a single permit.
     *
     * @return {@code true} if a permit was acquired and the caller may proceed;
     *         {@code false} otherwise.
     */
    boolean tryAcquire();
}

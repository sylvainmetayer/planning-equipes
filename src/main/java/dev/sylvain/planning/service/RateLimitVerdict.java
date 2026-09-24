package dev.sylvain.planning.service;

/**
 * What a rate limiter tells the caller: go ahead, or come back in so many
 * seconds.
 *
 * <p>One record for the three places that used to declare their own. {@link
 * SlidingWindowCounter} already argued the case for its arithmetic — "written
 * once rather than in each limiter […] a second hand-rolled copy is how the two
 * would drift" — and then the verdict itself was copied three times, javadoc
 * included. {@code CodeRequestLimiter} and {@code DeclarationRateLimiter} both
 * called the counter and re-wrapped its answer field for field, which is the
 * drift that argument warned about, one step further down.</p>
 *
 * <p>{@code secondsBeforeNextTry} is the delay left before the window reopens,
 * ready to be used as is for {@code Retry-After}.</p>
 */
public record RateLimitVerdict(boolean autorise, long secondsBeforeNextTry) {

    static RateLimitVerdict ok() {
        return new RateLimitVerdict(true, 0);
    }
}

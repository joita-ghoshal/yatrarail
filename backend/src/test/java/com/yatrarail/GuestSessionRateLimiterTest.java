package com.yatrarail;

import com.yatrarail.config.TemporarySessionProperties;
import com.yatrarail.security.GuestSessionRateLimiter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the per-IP guest session throttle.
 * <p>
 * Opening a session is anonymous and inserts a row, so this cap is the only thing standing between a
 * single client and a full users table. It counts successes, not failures.
 */
class GuestSessionRateLimiterTest {

    private GuestSessionRateLimiter limiterWith(int max, long windowMinutes) {
        TemporarySessionProperties properties = new TemporarySessionProperties();
        properties.setMaxSessionsPerIp(max);
        properties.setRateWindowMinutes(windowMinutes);
        return new GuestSessionRateLimiter(properties);
    }

    @Test
    @DisplayName("An IP is blocked only after it opens the maximum number of sessions")
    void blocksAtTheConfiguredLimit() {
        GuestSessionRateLimiter limiter = limiterWith(3, 60);

        assertThat(limiter.isBlocked("1.2.3.4")).isFalse();
        limiter.record("1.2.3.4");
        assertThat(limiter.isBlocked("1.2.3.4")).isFalse();
        limiter.record("1.2.3.4");
        assertThat(limiter.remaining("1.2.3.4")).isEqualTo(1);
        assertThat(limiter.isBlocked("1.2.3.4")).isFalse();

        limiter.record("1.2.3.4");
        assertThat(limiter.isBlocked("1.2.3.4")).isTrue();
        assertThat(limiter.remaining("1.2.3.4")).isZero();
    }

    @Test
    @DisplayName("The limit is per IP, so one abusive client cannot lock out everyone else")
    void limitsAreIsolatedPerIp() {
        GuestSessionRateLimiter limiter = limiterWith(2, 60);
        for (int i = 0; i < 5; i++) {
            limiter.record("10.0.0.1");
        }

        assertThat(limiter.isBlocked("10.0.0.1")).isTrue();
        assertThat(limiter.isBlocked("10.0.0.2")).isFalse();
        assertThat(limiter.remaining("10.0.0.2")).isEqualTo(2);
    }

    @Test
    @DisplayName("Creations age out of the window")
    void creationsExpireWithTheWindow() {
        GuestSessionRateLimiter limiter = limiterWith(2, 60);
        limiter.record("5.5.5.5");
        limiter.record("5.5.5.5");
        assertThat(limiter.isBlocked("5.5.5.5")).isTrue();

        // A zero-length window means everything already recorded is outside it.
        GuestSessionRateLimiter instant = limiterWith(2, 0);
        instant.record("6.6.6.6");
        assertThat(instant.isBlocked("6.6.6.6")).isFalse();
    }
}

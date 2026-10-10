package com.yatrarail.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Tunables for temporary (guest) one-time accounts. A guest session is deliberately short-lived and
 * every bound here is validated at startup so a misconfigured environment fails fast instead of
 * handing out long-lived guest tokens.
 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "yatrarail.temporary-session")
public class TemporarySessionProperties {

    /** How long a guest session stays valid, in minutes. */
    @NotNull
    @Min(5)
    @Max(1440)
    private Integer ttlMinutes = 120;

    /** Upper bound the requester cannot exceed, even if a future endpoint accepts a client hint. */
    @NotNull
    @Min(5)
    @Max(1440)
    private Integer maxTtlMinutes = 180;

    /** Cron expression for the job that revokes/purges stale guest sessions. */
    @NotNull
    private String cleanupCron = "0 */15 * * * *";

    /** Whether the cleanup job is enabled (disabled in tests that drive the lifecycle manually). */
    private boolean cleanupJobEnabled = true;

    /**
     * Max guest sessions one IP may open per window. Creating a session is an unauthenticated write
     * that inserts a row, so without this a single client could fill the users table for free.
     */
    @NotNull
    @Min(1)
    private Integer maxSessionsPerIp = 5;

    /** Minutes over which guest session creations are counted per IP. */
    @NotNull
    @Min(1)
    private Long rateWindowMinutes = 60L;

    /**
     * How long an expired, booking-less guest account is kept before it is hard-deleted. Accounts
     * that own bookings are never deleted by the job, so ticket history is never orphaned.
     */
    @NotNull
    @Min(0)
    private Integer retentionDays = 7;
}

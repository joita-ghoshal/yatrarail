package com.yatrarail.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Phase 10 — bounds for the in-process read cache.
 * <p>
 * A cache without a size bound is a memory leak with a friendly name, and one without a time bound
 * is a correctness bug waiting for a configuration change that is never noticed. Both are therefore
 * required settings rather than optional tuning knobs.
 * <p>
 * Only master data is cached (stations, routes, fare rules). Live seat inventory is deliberately
 * <b>not</b> cached anywhere: it changes with every booking, cancellation and expiry, and serving a
 * stale availability count is the one staleness this domain cannot tolerate.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "yatrarail.cache")
public class CacheProperties {

    /** Master switch: turning this off makes every {@code @Cacheable} call run afresh. */
    private boolean enabled = true;

    /** How long an entry may live before it is refetched, in seconds. */
    private long ttlSeconds = 300;

    /** Hard cap on the number of entries a single cache may hold. */
    private long maxSize = 10_000;
}

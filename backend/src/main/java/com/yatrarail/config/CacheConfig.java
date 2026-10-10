package com.yatrarail.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Phase 10 — the caching layer.
 * <p>
 * The point of building this on Spring's cache abstraction, rather than calling Caffeine directly,
 * is that the cache names are the contract: {@link CacheNames} lists every name the code may use,
 * the TTL and size are configuration, and a production deployment can replace this one bean with a
 * Redis {@code CacheManager} without touching a single {@code @Cacheable} annotation.
 * <p>
 * Metrics are not wired here on purpose. Because the manager is registered as a Spring bean, Boot's
 * {@code CacheMetricsRegistrar} binds it to Micrometer automatically, so {@code cache.gets},
 * {@code cache.puts} and {@code cache.evictions} appear on {@code /actuator/metrics} for free.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    /** Every cache the application may use. Naming them in one place stops typo'd caches appearing. */
    public static final class CacheNames {
        /** The station master list, read by every search box. */
        public static final String STATION_LIST = "stationList";
        /** A train's ordered route stops, keyed by train id. */
        public static final String TRAIN_ROUTE = "trainRoutes";
        /** The per-class fare rules, read by pricing and the admin fares page. */
        public static final String FARE_RULES = "fareRules";

        private CacheNames() {
        }

        public static List<String> all() {
            return List.of(STATION_LIST, TRAIN_ROUTE, FARE_RULES);
        }
    }

    @Bean
    public CacheManager cacheManager(CacheProperties properties) {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.setCacheNames(CacheNames.all());
        // A cache created lazily for an unknown name would silently miss the bounds below, so
        // caching is restricted to the names declared above.
        manager.setAllowNullValues(false);
        manager.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(properties.getTtlSeconds(), TimeUnit.SECONDS)
                .maximumSize(properties.getMaxSize())
                // Without recordStats() Caffeine tracks nothing, so Boot still *registers*
                // cache.gets/puts/evictions (because the manager is a bean) but every one of them
                // is permanently zero. A metric that is always zero is worse than no metric: it
                // reads as "no cache traffic" instead of "not measured".
                .recordStats());
        return manager;
    }
}

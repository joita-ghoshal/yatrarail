package com.yatrarail.observability;

import com.yatrarail.config.AsyncConfig;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Phase 10 — reports the saturation of the bounded async executor.
 * <p>
 * The queue is the buffer that lets a burst of non-critical work happen off the request thread, but
 * a buffer that is full is no longer a buffer: at that point the {@code CallerRunsPolicy} is pushing
 * work back onto the caller and latency will follow. Reporting the remaining capacity as
 * {@link Status#OUT_OF_SERVICE} for a full queue makes that moment a first-class, monitorable state
 * rather than something only visible as rising response times.
 */
@Component
public class PlatformQueueHealthIndicator implements HealthIndicator {

    private final ThreadPoolTaskExecutor executor;

    public PlatformQueueHealthIndicator(@Qualifier(AsyncConfig.PLATFORM_EXECUTOR) ThreadPoolTaskExecutor executor) {
        this.executor = executor;
    }

    @Override
    public Health health() {
        ThreadPoolExecutor pool;
        try {
            pool = executor.getThreadPoolExecutor();
        } catch (IllegalStateException notInitialised) {
            return Health.unknown().withDetail("reason", "executor not initialised").build();
        }

        int queueSize = pool.getQueue().size();
        int remaining = pool.getQueue().remainingCapacity();
        int active = pool.getActiveCount();
        int poolSize = pool.getPoolSize();

        Health.Builder builder = remaining == 0
                ? Health.outOfService()
                : Health.up();

        return builder
                .withDetail("queueSize", queueSize)
                .withDetail("queueRemainingCapacity", remaining)
                .withDetail("activeThreads", active)
                .withDetail("poolSize", poolSize)
                .withDetail("maxPoolSize", pool.getMaximumPoolSize())
                .withDetail("completedTasks", pool.getCompletedTaskCount())
                .build();
    }
}

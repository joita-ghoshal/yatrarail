package com.yatrarail.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Phase 10 — the bounded executor that non-critical, off-request work runs on.
 * <p>
 * The important line in this file is the rejection policy. A task executor with an unbounded queue
 * does not fail when it is overwhelmed; it accepts every task, the queue grows until the heap ends,
 * and the crash happens far from the cause. A bounded queue plus {@link
 * ThreadPoolExecutor.CallerRunsPolicy} instead applies back-pressure to the caller: once the pool
 * and queue are saturated, the publishing thread performs the work itself. Throughput degrades
 * visibly, the {@code platformExecutor} health indicator turns red, and the system stays up.
 * <p>
 * This is deliberately the last stop before a real broker. Moving to Kafka or RabbitMQ replaces the
 * publisher and the listener chain; the call sites, which already publish events, do not move.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    public static final String PLATFORM_EXECUTOR = "platformExecutor";

    @Bean(name = PLATFORM_EXECUTOR)
    public ThreadPoolTaskExecutor platformExecutor(PlatformProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getAsyncCorePoolSize());
        executor.setMaxPoolSize(properties.getAsyncMaxPoolSize());
        executor.setQueueCapacity(properties.getAsyncQueueCapacity());
        executor.setThreadNamePrefix("yatrarail-async-");
        // Back-pressure, not silent loss: when full, the caller runs the task.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // Drain in-flight events on shutdown so a deploy does not drop the last batch.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        executor.initialize();
        return executor;
    }
}

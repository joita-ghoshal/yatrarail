package com.yatrarail.observability;

import com.yatrarail.config.AsyncConfig;
import com.yatrarail.config.PlatformProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

/**
 * Phase 10 — the in-process implementation of {@link DomainEventPublisher}.
 * <p>
 * Two delivery paths, on purpose:
 * <ul>
 *   <li>the fact is always published as a synchronous Spring event, so the metrics listener records
 *       it on the request thread with no chance of a race;</li>
 *   <li>when {@code yatrarail.platform.async-enabled} is on, a structured log line is also handed to
 *       the bounded {@code platformExecutor}, which is where a real broker call would go. This
 *       exercises the queue (and its health indicator) without changing any domain outcome.</li>
 * </ul>
 * The publisher never propagates a listener failure: a booking that has committed must not be
 * reported as failed because a counter could not be incremented.
 */
@Component
public class PlatformEventPublisher implements DomainEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(PlatformEventPublisher.class);

    private final ApplicationEventPublisher events;
    private final ThreadPoolTaskExecutor platformExecutor;
    private final PlatformProperties properties;

    public PlatformEventPublisher(ApplicationEventPublisher events,
                                  ThreadPoolTaskExecutor platformExecutor,
                                  PlatformProperties properties,
                                  MeterRegistry registry) {
        this.events = events;
        this.platformExecutor = platformExecutor;
        this.properties = properties;
        // Bind a gauge to the underlying pool so its remaining capacity is visible next to the
        // queue-depth health indicator. The executor is initialised before it is injected, so the
        // real pool exists by the time this constructor runs.
        registry.gauge("yatrarail.platform.async.queue.capacity",
                platformExecutor.getThreadPoolExecutor(),
                tpe -> tpe.getQueue().remainingCapacity());
    }

    @Override
    public void publish(PlatformEvent event) {
        try {
            events.publishEvent(event);
        } catch (RuntimeException ex) {
            log.warn("Synchronous listener for {} failed: {}", event.type(), ex.getMessage());
        }
        if (properties.isAsyncEnabled()) {
            try {
                platformExecutor.execute(() -> log.info(
                        "event dispatched type={} reference={} occurredAt={}",
                        event.type(), event.reference(), event.occurredAt()));
            } catch (RuntimeException ex) {
                // The bounded queue's CallerRunsPolicy makes this near-impossible, but a shutdown
                // race must still never fail a committed write.
                log.warn("Async dispatch of {} refused: {}", event.type(), ex.getMessage());
            }
        }
    }

    /** Exposed for tests and diagnostics rather than reached through the interface. */
    public ThreadPoolTaskExecutor executor() {
        return platformExecutor;
    }
}

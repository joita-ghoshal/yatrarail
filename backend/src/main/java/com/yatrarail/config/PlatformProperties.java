package com.yatrarail.config;

import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Phase 10 — the bounded thread pool that stands in for a message broker.
 * <p>
 * Today the platform's non-critical fan-out (metrics, audit-style side logging) is an in-process
 * {@code ApplicationEvent} published to a bounded executor. The bounds are the important part: a
 * finite queue with a caller-runs rejection policy turns overload into visible back-pressure rather
 * than an {@code OutOfMemoryError}, and the {@code platformExecutor} health indicator reports the
 * saturation so a monitor can see it coming.
 * <p>
 * When a real broker (RabbitMQ, Kafka, SQS) is introduced, only the publisher and listener change;
 * the call sites and their event boundaries stay exactly where they are.
 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "yatrarail.platform")
public class PlatformProperties {

    /**
     * Whether events are fanned out asynchronously. Off by default so a local stack trace names the
     * request thread, and so tests never race a background worker. The event is always published;
     * this only decides whether a listener runs on the pool or on the caller.
     */
    private boolean asyncEnabled = false;

    @Min(1)
    private int asyncCorePoolSize = 4;

    @Min(1)
    private int asyncMaxPoolSize = 16;

    /** Bound on queued events. Once full, the rejecting policy runs the task on the caller. */
    @Min(0)
    private int asyncQueueCapacity = 500;

    /** A request slower than this is counted as slow and logged at WARN, in milliseconds. */
    @Min(0)
    private long slowRequestMs = 1_000;
}

package com.yatrarail.observability;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Phase 10 — turns every published {@link PlatformEvent} into a Micrometer counter, so the domain's
 * traffic shows up on {@code /actuator/metrics/yatrarail.domain.events} without any service having
 * to know that metrics exist.
 * <p>
 * The listener runs on the publishing thread and must therefore stay cheap: incrementing a counter
 * is O(1) and does not block. Anything heavier belongs on the async executor.
 */
@Component
public class PlatformEventMetricsListener {

    private final MeterRegistry registry;

    public PlatformEventMetricsListener(MeterRegistry registry) {
        this.registry = registry;
    }

    @EventListener
    public void onPlatformEvent(PlatformEvent event) {
        registry.counter("yatrarail.domain.events", "type", event.type()).increment();
    }
}

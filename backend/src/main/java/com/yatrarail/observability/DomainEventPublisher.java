package com.yatrarail.observability;

/**
 * Phase 10 — the seam a message broker will one day sit behind.
 * <p>
 * The service layer depends on this interface and nothing else. Today the implementation is an
 * in-process Spring {@code ApplicationEventPublisher}; tomorrow it can be a Kafka template or an
 * outbox table, and no service changes. That is the whole point of introducing it before it is
 * needed.
 */
public interface DomainEventPublisher {

    /**
     * Publishes a fact. Implementations must not throw for a listener failure and must not be
     * relied on for transactional delivery — the write has already happened.
     */
    void publish(PlatformEvent event);
}

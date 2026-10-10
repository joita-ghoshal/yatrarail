package com.yatrarail.observability;

import java.time.Instant;
import java.util.Map;

/**
 * Phase 10 — a domain fact, published after it has already been persisted.
 * <p>
 * This is intentionally a dumb, serialisable record: a type, a reference to the aggregate it
 * concerns, when it happened, and a small bag of scalar attributes. It carries no entities and no
 * lazy JPA associations, so it can be handled on another thread today and shipped to another
 * process later without dragging the persistence context along.
 * <p>
 * Publishing is not a transaction boundary. The event describes something that has <b>already</b>
 * committed; a listener that fails must not roll the booking back, which is why async handlers are
 * terminal (metrics, notifications) rather than part of the write path.
 *
 * @param type       the fact, e.g. {@code BOOKING_CREATED} or {@code PAYMENT_SETTLED}
 * @param reference  the human-facing identifier the fact is about (booking reference, refund ref)
 * @param occurredAt when the fact happened
 * @param attributes small, scalar, non-sensitive extras for the listener (never secrets or PII)
 */
public record PlatformEvent(String type, String reference, Instant occurredAt,
                            Map<String, String> attributes) {

    public PlatformEvent {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("PlatformEvent.type is required");
        }
        occurredAt = occurredAt == null ? Instant.now() : occurredAt;
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    /** The common shape: a type and a reference, happening now. */
    public static PlatformEvent of(String type, String reference) {
        return new PlatformEvent(type, reference, Instant.now(), Map.of());
    }

    public static PlatformEvent of(String type, String reference, Map<String, String> attributes) {
        return new PlatformEvent(type, reference, Instant.now(), attributes);
    }
}

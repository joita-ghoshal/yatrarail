package com.yatrarail.entity;

import java.util.Arrays;
import java.util.Optional;

/**
 * The closed set of things this platform notifies someone about.
 * <p>
 * <b>Why an enum when the column is still a {@code String}.</b> The wire value is the constant name,
 * but the column deliberately stays {@code VARCHAR} rather than becoming an
 * {@code @Enumerated(STRING)} enum column. A JPA enum column makes Hibernate generate a CHECK
 * constraint listing every value, and {@code ddl-auto=update} never widens a CHECK that already
 * exists — so the first new notification type after deployment would be rejected by the database
 * and the notifier would fail silently, forever, on exactly the code path that matters most (see
 * B22 in project.md, where new booking statuses hit precisely this trap). A string column with an
 * enum in Java gets the compile-time safety without the migration that every future type would
 * otherwise need. {@code fromWire} keeps an unrecognised legacy value readable rather than fatal.
 * <p>
 * <b>Why some of these cannot be switched off.</b> {@link #suppressible} is the rule that keeps
 * notification preferences from becoming a way to lose money. Most of what this platform sends is
 * the receipt of something the recipient just did — their seat is held, their payment failed, their
 * waitlist place moved. A user who ticks "do not tell me about my bookings" and then misses the
 * fact that their ticket expired has not configured a preference, they have lost a ticket. Only the
 * genuinely advisory item — a system-wide announcement nobody personally triggered — is the
 * recipient's to refuse.
 */
public enum NotificationType {

    // ---- transactional: the receipt of something the recipient did. Never suppressible.
    BOOKING_CONFIRMATION("Booking updates", "Booking confirmed", false),
    PAYMENT_STATUS("Payment updates", "Payment update", false),
    CANCELLATION("Cancellation updates", "Booking cancelled", false),
    BOOKING_EXPIRED("Booking updates", "Booking expired", false),

    /** A waitlist entry is the outcome of a search, not the terminal state; the terminal state is WAITLIST_UPDATE. */
    WAITLIST_JOINED("Waitlist updates", "Added to the waitlist", true),
    /** Somebody ahead of this passenger left the queue. The passenger's place genuinely improved. */
    WAITLIST_MOVED("Waitlist updates", "Moved up the waitlist", true),
    /** The terminal outcome: a seat, or an RAC berth. Always delivered. */
    WAITLIST_UPDATE("Waitlist updates", "Waitlist outcome", false),

    /** A broadcast. Nobody triggered it individually, so it is the recipient's to refuse. */
    SYSTEM_ANNOUNCEMENT("System announcements", "Announcement", true),

    // ---- staff operational alerts. Somebody has to act on these, so they are never suppressible.
    ASSISTANCE_REQUEST("Staff alerts", "Assistance requested", false),
    STAFF_INCIDENT("Staff alerts", "Incident reported", false);

    private final String categoryLabel;
    private final String label;
    private final boolean suppressible;

    NotificationType(String categoryLabel, String label, boolean suppressible) {
        this.categoryLabel = categoryLabel;
        this.label = label;
        this.suppressible = suppressible;
    }

    /** The preference-group name shown on the settings screen. */
    public String getCategoryLabel() {
        return categoryLabel;
    }

    /** Short human name for this kind of notification, used in the feed and in tests. */
    public String getLabel() {
        return label;
    }

    /** True when a recipient is allowed to opt out of this type. See the class comment. */
    public boolean isSuppressible() {
        return suppressible;
    }

    /**
     * Resolves a stored {@code notifications.type} value. An unknown or legacy value yields an empty
     * Optional rather than an exception: an old row must still render in the feed, because losing a
     * notification because its label is unknown is a worse failure than showing a raw string.
     */
    public static Optional<NotificationType> fromWire(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String trimmed = value.trim();
        return Arrays.stream(values())
                .filter(t -> t.name().equalsIgnoreCase(trimmed))
                .findFirst();
    }
}
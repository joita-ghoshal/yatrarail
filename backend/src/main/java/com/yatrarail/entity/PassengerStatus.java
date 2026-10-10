package com.yatrarail.entity;

/**
 * Per-passenger allocation state inside a booking. Replaces the previous free-text string so that
 * invalid values are impossible and the reservation engine can rely on the enum.
 * <p>
 * Allocation priority, strongest first:
 * {@link #CONFIRMED} (own seat) &gt; {@link #RAC} (Reservation Against Cancellation — travelling on
 * a confirmed ticket that may be upgraded when a confirmed seat is cancelled) &gt;
 * {@link #WAITLISTED} (no seat yet).
 */
public enum PassengerStatus {
    /** Own allocated seat/berth. */
    CONFIRMED,
    /** Reservation Against Cancellation — a berth is held but the seat may be shared. */
    RAC,
    /** On the waitlist, waiting for a free seat. */
    WAITLISTED,
    /** Cancelled by the passenger (or as part of the whole booking). */
    CANCELLED,
    /** The unpaid hold expired before payment; the seat was returned to inventory. */
    EXPIRED
}

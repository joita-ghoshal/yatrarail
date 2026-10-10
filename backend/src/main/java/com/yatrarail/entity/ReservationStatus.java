package com.yatrarail.entity;

/**
 * Lifecycle of a physical seat hold for one journey date.
 * <p>
 * {@link #HELD} — the seat is locked for a booking that is still awaiting payment. The hold has a
 * deadline ({@code expiresAt}); an expired hold is released by the pending-expiry job and the seat
 * becomes bookable again.
 * <p>
 * {@link #CONFIRMED} — the booking was paid for and the seat is permanently allocated for that date.
 * <p>
 * A released seat has its {@code seat_reservations} row physically deleted, which keeps the
 * {@code (seat_id, journey_date)} unique constraint meaningful as the last line of defence against
 * double booking.
 */
public enum ReservationStatus {
    HELD,
    CONFIRMED
}

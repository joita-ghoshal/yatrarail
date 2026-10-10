package com.yatrarail.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A seat hold for exactly one {@code (seat, journeyDate)} pair.
 * <p>
 * This table is the authoritative inventory of the reservation engine. A seat is bookable only when
 * no row exists here for that seat and journey date. The {@code (seat_id, journey_date)} unique
 * constraint is the hard, database-level guarantee that two concurrent bookings can never be
 * granted the same seat — pessimistic locking in the service layer serialises allocation so that
 * callers get a clean conflict error instead of a raw constraint violation.
 * <p>
 * Rows are deleted (not flagged) whenever a hold is released, so the unique constraint never blocks
 * a legitimate re-booking.
 */
@Entity
@Table(name = "seat_reservations",
        uniqueConstraints = @UniqueConstraint(name = "uk_seat_reservation_seat_date",
                columnNames = {"seat_id", "journey_date"}),
        indexes = {
                @Index(name = "idx_resv_seat_date", columnList = "seat_id, journey_date"),
                @Index(name = "idx_resv_passenger", columnList = "booking_passenger_id"),
                @Index(name = "idx_resv_journey", columnList = "journey_date"),
                @Index(name = "idx_resv_hold_expiry", columnList = "status, expires_at"),
                @Index(name = "idx_resv_quota_usage", columnList = "train_id, journey_date, status, quota_type")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SeatReservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "seat_id", nullable = false)
    private Seat seat;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "booking_passenger_id", nullable = false)
    private BookingPassenger bookingPassenger;

    @Column(name = "journey_date", nullable = false)
    private LocalDate journeyDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReservationStatus status;

    /** Deadline for a {@link ReservationStatus#HELD} row; null once confirmed. */
    private LocalDateTime expiresAt;

    @Column(name = "coach_id", nullable = false)
    private Long coachId;

    @Column(name = "train_id", nullable = false)
    private Long trainId;

    /**
     * The quota whose slice of the class inventory this reservation consumes. Quota capacity is
     * counted from this table rather than from the booking rows, because a hold is a real claim on
     * a seat: an unpaid booking that is holding a seat has already used its quota, and cancelling or
     * expiring it releases it again.
     */
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "quota_type", nullable = false, length = 20)
    private QuotaType quota = QuotaType.GENERAL;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public boolean isActiveHold(LocalDateTime now) {
        return status == ReservationStatus.HELD && expiresAt != null && expiresAt.isAfter(now);
    }
}

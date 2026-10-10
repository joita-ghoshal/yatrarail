package com.yatrarail.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One cancellation event: either a whole booking, or a single passenger within it.
 * <p>
 * <b>Phase 5 change.</b> This used to be a strict one-to-one with {@link Booking}
 * ({@code booking_id UNIQUE NOT NULL}), which made a partial cancellation impossible — cancelling
 * one passenger of a three-passenger booking had to be modelled as cancelling all three, or not at
 * all. The relationship is now many-to-one, and a row additionally names the
 * {@link BookingPassenger} it applies to:
 * <ul>
 *   <li>{@code bookingPassenger == null} means the row covers the whole booking. This is what every
 *       pre-Phase-5 row means, so old data stays readable without a rewrite;</li>
 *   <li>a {@code bookingPassenger} means exactly that passenger was cancelled, and the refund is
 *       computed for their share alone.</li>
 * </ul>
 * A database-level unique constraint enforces "a passenger can only be cancelled once", so a
 * double-submitted cancellation cannot produce a second refund for the same seat.
 * <p>
 * <b>Why the breakdown columns.</b> {@link #refundAmount} alone cannot be audited: a passenger
 * asking why they were refunded 40% of what they paid needs the per-passenger fare, the GST share
 * and the platform-fee share that went into the calculation. Storing them on the row means the
 * answer survives a later tariff change, exactly as the Phase 4 fare snapshot does for the price.
 */
@Entity
@Table(name = "cancellations", indexes = {
        @Index(name = "idx_cancel_booking", columnList = "booking_id, cancelled_at"),
        @Index(name = "idx_cancel_passenger", columnList = "booking_passenger_id"),
        @Index(name = "idx_cancel_status", columnList = "refund_status"),
        // Phase 10: the admin refund ledger is paged newest-first, and a refund reference is quoted
        // in support conversations and searched for verbatim.
        @Index(name = "idx_cancel_cancelled_at", columnList = "cancelled_at"),
        @Index(name = "idx_cancel_refund_ref", columnList = "refund_reference")
}, uniqueConstraints = {
        // A passenger can only be cancelled once, so a replayed request cannot pay twice. Declared
        // here as well as in phase5-migration.sql, because a brand-new database is built by
        // `ddl-auto=update` from this annotation alone and would otherwise miss the invariant.
        @UniqueConstraint(name = "uk_cancellation_passenger", columnNames = "booking_passenger_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Cancellation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The booking this cancellation belongs to. Many-to-one as of Phase 5: a booking can accumulate
     * several rows — one per cancelled passenger, or a single row for the whole thing.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "booking_id", nullable = false)
    private Booking booking;

    /**
     * The single passenger this row cancels, or {@code null} when the row cancels the whole booking.
     * A pre-Phase-5 row has no passenger id and means the same thing, so legacy data needs no backfill.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "booking_passenger_id")
    private BookingPassenger bookingPassenger;

    @Column(length = 255)
    private String reason;

    /** Total money returned to the passenger: the sum of the itemised lines below. */
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal refundAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "refund_status", nullable = false, length = 20)
    private RefundStatus refundStatus;

    /**
     * Whether this row cancelled the whole booking or part of it. Derived from the passengers at
     * write time rather than recomputed on read, so the ledger keeps the historical truth even if
     * the booking is later edited.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "scope", length = 20)
    private CancellationScope scope;

    // ---- the itemised refund, so a refund can explain itself later ----

    /**
     * The passenger-fare share of {@link #refundAmount}. GST is reported separately because it is
     * levied on the fare subtotal rather than being part of it.
     */
    @Builder.Default
    @Column(name = "fare_refunded", precision = 10, scale = 2)
    private BigDecimal fareRefunded = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "gst_refunded", precision = 10, scale = 2)
    private BigDecimal gstRefunded = BigDecimal.ZERO;

    /**
     * The platform-fee share returned. Non-zero only on a full cancellation, because the fee is
     * charged once per booking and the booking-level charge is not refunded while the booking
     * survives.
     */
    @Builder.Default
    @Column(name = "platform_fee_refunded", precision = 10, scale = 2)
    private BigDecimal platformFeeRefunded = BigDecimal.ZERO;

    /**
     * Everything that was charged but not returned: what the tiered cancellation charge and the
     * non-refundable platform-fee share add up to.
     */
    @Builder.Default
    @Column(name = "cancellation_charge", precision = 10, scale = 2)
    private BigDecimal cancellationCharge = BigDecimal.ZERO;

    /** The tier percentage actually applied, so the row still makes sense if the policy changes. */
    @Column(name = "refund_percent", precision = 5, scale = 2)
    private BigDecimal refundPercent;

    /** Days between the cancellation and departure — the input that chose the tier. */
    @Column(name = "days_before_journey")
    private Long daysBeforeJourney;

    /** YatraRail refund reference, shown to the passenger and quoted in support conversations. */
    @Column(name = "refund_reference", length = 40)
    private String refundReference;

    /** When the refund was settled; null while it is still PENDING. */
    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    @Column(nullable = false, updatable = false)
    private LocalDateTime cancelledAt;

    @PrePersist
    protected void onCreate() {
        cancelledAt = LocalDateTime.now();
        if (processedAt == null && refundStatus == RefundStatus.PROCESSED) {
            processedAt = cancelledAt;
        }
    }
}

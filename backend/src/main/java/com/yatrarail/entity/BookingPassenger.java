package com.yatrarail.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;
import java.math.BigDecimal;

@Entity
@Table(name = "booking_passengers", indexes = {
        @Index(name = "idx_bp_seat", columnList = "seat_id"),
        @Index(name = "idx_bp_booking", columnList = "booking_id"),
        @Index(name = "idx_bp_status", columnList = "passenger_status"),
        @Index(name = "idx_bp_journey_queue",
                columnList = "passenger_status, waitlist_position, id"),
        @Index(name = "idx_bp_quota", columnList = "quota_type")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BookingPassenger {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "booking_id", nullable = false)
    @JsonIgnore
    private Booking booking;

    @Column(nullable = false, length = 120)
    private String fullName;

    @Column(nullable = false)
    private Integer age;

    @Column(nullable = false, length = 10)
    private String gender;

    @Column(length = 20)
    private String berthPreference; // LOWER, MIDDLE, UPPER, SIDE_LOWER, SIDE_UPPER, NO_PREFERENCE

    /** Assigned seat for this journey date; null while RAC or waitlisted. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "seat_id")
    private Seat seat;

    @Enumerated(EnumType.STRING)
    @Column(name = "passenger_status", length = 20)
    private PassengerStatus passengerStatus;

    /**
     * Stable queue position in the RAC / waitlist for this coach + journey date. Assigned when the
     * passenger enters the queue, kept when they are promoted, so ordering never depends on
     * "whoever we happened to load first".
     */
    @Column(name = "waitlist_position")
    private Integer waitlistPosition;

    // ------------------------------------------------------------------
    // Phase 4 — quota assignment and the passenger's slice of the fare breakdown.
    // ------------------------------------------------------------------

    /**
     * The quota this passenger is actually booked under. It is not always the quota they asked for:
     * when a reserved quota has no seats left the allocation engine books them under
     * {@link QuotaType#GENERAL} at the full fare and records why in {@link #quotaNote}. Once written
     * it never changes, so the fare charged and the quota claimed can never drift apart.
     */
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "quota_type", nullable = false, length = 20)
    private QuotaType quota = QuotaType.GENERAL;

    /** Concession actually applied to this passenger, 0-90. Snapshotted from the tariff at booking. */
    @Builder.Default
    @Column(name = "quota_discount_percent", nullable = false)
    private Integer quotaDiscountPercent = 0;

    /** Net amount charged for this passenger: gross fare − concession. */
    @Builder.Default
    @Column(name = "fare_amount", precision = 10, scale = 2)
    private BigDecimal fareAmount = BigDecimal.ZERO;

    /** Why the passenger was booked under a different quota than the one requested, if they were. */
    @Column(name = "quota_note", length = 200)
    private String quotaNote;

    // ------------------------------------------------------------------
    // Phase 6 — gate check-in. Operational record only; see BoardingStatus for why this
    // deliberately cannot touch money.
    // ------------------------------------------------------------------

    /**
     * Gate state for this passenger. {@code NOT_BOARDED} is the default in three places on purpose:
     * the Java field, the Lombok builder, and the DDL default below.
     * <p>
     * {@code @Builder.Default} alone is not enough: it only affects code that uses the builder, so a
     * database created from scratch by {@code ddl-auto=update} would get a <i>nullable</i> column
     * with no default, and a row inserted by anything other than this builder would land on NULL and
     * disappear from the dashboard's boarding counts. {@code @ColumnDefault} is what actually reaches
     * the generated DDL.
     */
    @Builder.Default
    @ColumnDefault("'NOT_BOARDED'")
    @Enumerated(EnumType.STRING)
    @Column(name = "boarding_status", nullable = false, length = 20)
    private BoardingStatus boardingStatus = BoardingStatus.NOT_BOARDED;

    /** Staff member who marked the boarding state, so a mis-scan can be traced and corrected. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "boarding_marked_by_id")
    private User boardingMarkedBy;

    @Column(name = "boarding_marked_at")
    private java.time.LocalDateTime boardingMarkedAt;
}

package com.yatrarail.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "bookings", uniqueConstraints = @UniqueConstraint(columnNames = "booking_reference"),
        indexes = {
                @Index(name = "idx_booking_user", columnList = "user_id"),
                @Index(name = "idx_booking_train_date", columnList = "train_id, journey_date"),
                @Index(name = "idx_booking_status", columnList = "status"),
                @Index(name = "idx_booking_expiry", columnList = "status, payment_deadline"),
                @Index(name = "idx_booking_user_train_date", columnList = "user_id, train_id, journey_date"),
                // Phase 10: the admin booking and payment ledgers are ordered by creation, and a
                // passenger's own list orders their bookings by creation within their user id.
                @Index(name = "idx_booking_created", columnList = "created_at"),
                @Index(name = "idx_booking_user_created", columnList = "user_id, created_at"),
                // Phase 10: the reports filter and group by journey date across all trains.
                @Index(name = "idx_booking_journey_date", columnList = "journey_date")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** YatraRail-generated booking reference. NOT an official Indian Railways PNR. */
    @Column(name = "booking_reference", nullable = false, unique = true, length = 20)
    private String bookingReference;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "train_id", nullable = false)
    private Train train;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "coach_id", nullable = false)
    private Coach coach;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_station_id", nullable = false)
    private Station sourceStation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "destination_station_id", nullable = false)
    private Station destinationStation;

    @Column(nullable = false)
    private LocalDate journeyDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BookingStatus status;

    /**
     * Grand total actually charged: passengerFareTotal − discountTotal + platformFee + gstAmount.
     * (Before Phase 4 this was simply farePerPassenger × passengerCount, which is the gross.)
     */
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal totalFare;

    /** Distance of the *booked segment* (source -> destination), not the train's full route. */
    @Builder.Default
    @Column(name = "distance_km", nullable = false)
    private Integer distanceKm = 0;

    /** Fare for one passenger over the booked segment. totalFare = farePerPassenger * passengerCount. */
    @Builder.Default
    @Column(name = "fare_per_passenger", nullable = false, precision = 10, scale = 2)
    private BigDecimal farePerPassenger = BigDecimal.ZERO;

    // ------------------------------------------------------------------
    // Phase 4 — immutable fare-breakdown snapshot.
    // Every value below is copied off the live tariff at booking time, so the ticket can always
    // explain how the price was reached even after an administrator changes the fare rules.
    // The new total is: totalFare = passengerFareTotal + platformFee + gstAmount, where
    // passengerFareTotal = Σ (gross fare − quota concession) over the booking's passengers.
    // `farePerPassenger` keeps its Phase 2 meaning: the *gross* fare for one passenger before any
    // quota concession, i.e. max(coachBaseFare, farePerKm × distanceKm, minimumFare).
    // ------------------------------------------------------------------

    /** The coach's base fare at booking time - one of the three candidates for the gross fare. */
    @Builder.Default
    @Column(name = "fare_base", precision = 10, scale = 2)
    private BigDecimal fareBase = BigDecimal.ZERO;

    /** The class's fare-per-km at booking time. */
    @Builder.Default
    @Column(name = "fare_per_km", precision = 10, scale = 2)
    private BigDecimal farePerKm = BigDecimal.ZERO;

    /** The class's minimum fare at booking time. */
    @Builder.Default
    @Column(name = "fare_minimum", precision = 10, scale = 2)
    private BigDecimal fareMinimum = BigDecimal.ZERO;

    /** farePerKm × distanceKm - the distance-based candidate for the gross fare. */
    @Builder.Default
    @Column(name = "distance_fare", precision = 10, scale = 2)
    private BigDecimal distanceFare = BigDecimal.ZERO;

    /** Which of the three candidates actually set the gross fare: COACH_BASE, DISTANCE or MINIMUM. */
    @Column(name = "fare_basis", length = 20)
    private String fareBasis;

    /** Σ gross fare of the booking's passengers, before concessions. */
    @Builder.Default
    @Column(name = "passenger_fare_total", precision = 10, scale = 2)
    private BigDecimal passengerFareTotal = BigDecimal.ZERO;

    /** Σ quota concessions granted on this booking. */
    @Builder.Default
    @Column(name = "discount_total", precision = 10, scale = 2)
    private BigDecimal discountTotal = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "platform_fee", precision = 10, scale = 2)
    private BigDecimal platformFee = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "gst_percent", precision = 5, scale = 2)
    private BigDecimal gstPercent = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "gst_amount", precision = 10, scale = 2)
    private BigDecimal gstAmount = BigDecimal.ZERO;

    /**
     * Client-supplied idempotency key for booking creation. Replaying the same key returns the
     * original booking instead of creating a second one, so a double-click or a network retry can
     * never double-book.
     */
    @Column(name = "idempotency_key", unique = true, length = 80)
    private String idempotencyKey;

    /** Deadline by which a PAYMENT_PENDING booking must be paid, otherwise its seats are released. */
    @Column(name = "payment_deadline")
    private LocalDateTime paymentDeadline;

    @OneToMany(mappedBy = "booking", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<BookingPassenger> passengers = new ArrayList<>();

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}

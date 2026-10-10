package com.yatrarail.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * Documented, simplified demo fare model: fare = baseFarePerKm[class] * distanceKm,
 * with a cancellation refund percentage tiered by how far ahead of the journey date
 * the cancellation happens. This does not represent an official Indian Railways tariff.
 * <p>
 * <b>Phase 4:</b> a class also carries the two charges that turn a single number into a full fare
 * breakdown - a flat platform/convenience fee per booking and a GST percentage. Both are
 * administrator-configurable and both are snapshotted onto the booking at creation time, so a later
 * tariff change can never retroactively alter what a passenger was quoted.
 */
@Entity
@Table(name = "fare_rules")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FareRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, unique = true, length = 20)
    private SeatClass seatClass;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal farePerKm;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal minimumFare;

    /** Flat per-booking platform / convenience charge for this class, in rupees. */
    @Builder.Default
    @Column(name = "platform_fee", nullable = false, precision = 10, scale = 2)
    private BigDecimal platformFee = BigDecimal.ZERO;

    /** GST percentage applied to the passenger fare subtotal (not to {@link #platformFee}). */
    @Builder.Default
    @Column(name = "gst_percent", nullable = false, precision = 5, scale = 2)
    private BigDecimal gstPercent = BigDecimal.ZERO;
}

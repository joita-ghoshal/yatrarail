package com.yatrarail.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * The fare concession a given quota receives in a given class, e.g. 40% off for a senior citizen in
 * third AC.
 * <p>
 * Kept separate from {@link QuotaConfig} on purpose: the concession is a property of
 * (class × quota) and is identical for every train, whereas the capacity split is a property of
 * (train × class). An administrator therefore configures "how much does a senior citizen pay" once
 * and "how many senior seats does train 12301 keep" per train.
 * <p>
 * The concession is applied to the quota a passenger is <i>actually</i> booked under, which is
 * decided by {@code SeatAllocationService} under the coach lock. A passenger whose reserved quota
 * is full is booked under the general quota and therefore pays the full fare - the quote shows this
 * before the booking is created, so it is never a surprise.
 */
@Entity
@Table(name = "quota_fares",
        uniqueConstraints = @UniqueConstraint(name = "uk_quota_fare_class_quota",
                columnNames = {"seat_class", "quota_type"}),
        indexes = @Index(name = "idx_quota_fare_class", columnList = "seat_class"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QuotaFare {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "seat_class", nullable = false, length = 20)
    private SeatClass seatClass;

    @Enumerated(EnumType.STRING)
    @Column(name = "quota_type", nullable = false, length = 20)
    private QuotaType quotaType;

    /** Whole-percentage concession, 0-90. 90 is the ceiling so a fare can never round to nothing. */
    @Builder.Default
    @Column(name = "discount_percent", nullable = false)
    private Integer discountPercent = 0;

    /** Free-text note shown to administrators, e.g. why a class has no senior concession. */
    @Column(length = 200)
    private String note;
}

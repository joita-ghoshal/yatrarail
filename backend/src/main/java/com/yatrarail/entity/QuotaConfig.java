package com.yatrarail.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * The administrator-managed quota split for one class of one train, e.g. "third AC on train 12301
 * keeps 6 seats aside for ladies and 4 for senior citizens, and 2 Tatkal seats open the day before".
 * <p>
 * Only the reserved slices are stored. The general slice is derived at read time from the class's
 * current physical capacity, so adding or removing a coach automatically changes the general quota
 * and an administrator can never save a split that adds up to more seats than the train owns.
 * <p>
 * Seats are held for one journey date, so every number here is a <i>per journey date</i> allowance.
 */
@Entity
@Table(name = "quota_configs",
        uniqueConstraints = @UniqueConstraint(name = "uk_quota_config_train_class",
                columnNames = {"train_id", "seat_class"}),
        indexes = @Index(name = "idx_quota_config_train", columnList = "train_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QuotaConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "train_id", nullable = false)
    private Train train;

    @Enumerated(EnumType.STRING)
    @Column(name = "seat_class", nullable = false, length = 20)
    private SeatClass seatClass;

    @Builder.Default
    @Column(name = "tatkal_seats", nullable = false)
    private Integer tatkalSeats = 0;

    @Builder.Default
    @Column(name = "senior_seats", nullable = false)
    private Integer seniorSeats = 0;

    @Builder.Default
    @Column(name = "ladies_seats", nullable = false)
    private Integer ladiesSeats = 0;

    @Builder.Default
    @Column(name = "divyang_seats", nullable = false)
    private Integer divyangSeats = 0;

    @Builder.Default
    @Column(name = "staff_seats", nullable = false)
    private Integer staffSeats = 0;

    /**
     * How many days before the journey date the Tatkal-sim quota may be used. {@code 1} means
     * "today and tomorrow", which is the closest day-granular approximation of the real short
     * emergency window this demo models.
     */
    @Builder.Default
    @Column(name = "tatkal_window_days", nullable = false)
    private Integer tatkalWindowDays = 1;

    /**
     * When false the whole class falls back to a single unreserved general pool, without deleting
     * the configured split, so an administrator can switch quotas off for a season and back on.
     */
    @Builder.Default
    @Column(nullable = false)
    private Boolean active = true;

    /**
     * Whether this split is enforced. A {@code null} flag is treated as enabled, so a row written by
     * an older build can never silently disable quotas.
     */
    public boolean isEnabled() {
        return !Boolean.FALSE.equals(active);
    }

    public int reservedSeatsFor(QuotaType quota) {
        if (quota == null) {
            return 0;
        }
        return switch (quota) {
            case TATKAL -> safe(tatkalSeats);
            case SENIOR -> safe(seniorSeats);
            case LADIES -> safe(ladiesSeats);
            case DIVYANG -> safe(divyangSeats);
            case STAFF -> safe(staffSeats);
            case GENERAL -> 0;
        };
    }

    /** Sum of every reserved slice; must never exceed the class's physical capacity. */
    public int reservedSeats() {
        return safe(tatkalSeats) + safe(seniorSeats) + safe(ladiesSeats)
                + safe(divyangSeats) + safe(staffSeats);
    }

    private static int safe(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }
}

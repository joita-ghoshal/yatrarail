package com.yatrarail.dto.booking;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PassengerResultDto {
    /**
     * Phase 5 — the {@code booking_passengers} id of this passenger line.
     * <p>
     * Passenger-level cancellation and the refund preview are both keyed on this id, so it has to
     * travel with the passenger in every view that offers the action. It identifies a line inside
     * the caller's own booking only, and is already exposed to the same caller by the refund
     * preview, so carrying it here discloses nothing new.
     */
    private Long id;
    private String fullName;
    private Integer age;
    private String gender;
    private String berthPreference;
    private String coachCode;
    private Integer seatNumber;
    private String berthType;
    /** CONFIRMED, RAC, WAITLISTED, CANCELLED or EXPIRED. */
    private String passengerStatus;
    /** Queue position within the coach for this journey date, when on RAC or waitlisted. */
    private Integer waitlistPosition;
    /** True for a RAC entry: the seat may be shared until a confirmed booking is cancelled. */
    private Boolean rac;
    /**
     * Phase 4 — the quota this passenger is actually booked under. Differs from the requested quota
     * when a reserved quota was full, in which case {@link #quotaNote} explains the substitution and
     * the passenger paid the general fare.
     */
    private String quota;
    private String quotaLabel;
    /** Concession applied to this passenger, 0-90. */
    private Integer quotaDiscountPercent;
    /** Net amount charged for this passenger after the concession. */
    private java.math.BigDecimal fareAmount;
    private String quotaNote;
}

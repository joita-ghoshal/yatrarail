package com.yatrarail.dto.train;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClassAvailability {
    private Long coachId;
    private String coachCode;
    private String seatClass;
    private String seatClassLabel;
    /** Fare for one passenger over the *searched segment*, not the train's full route. */
    private BigDecimal fare;
    private Integer distanceKm;
    private int totalSeats;
    /** Seats with a live hold or a confirmed reservation. */
    private int availableSeats;
    /** Remaining RAC (Reservation Against Cancellation) slots for this coach and date. */
    private int racSeats;
    /** AVAILABLE, RAC_AVAILABLE or WAITLIST. */
    private String status;
}

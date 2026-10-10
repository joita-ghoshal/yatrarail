package com.yatrarail.dto.admin;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class CoachRequest {
    @NotBlank(message = "Coach code is required")
    @Size(max = 10, message = "Coach code must be at most 10 characters")
    private String coachCode;

    @NotBlank(message = "Seat class is required")
    @Size(max = 20, message = "Seat class must be at most 20 characters")
    private String seatClass;

    @NotNull(message = "Total seats is required")
    @Positive(message = "Total seats must be positive")
    @Max(value = 200, message = "Total seats must be at most 200")
    private Integer totalSeats;

    @NotNull(message = "Base fare is required")
    @Positive(message = "Base fare must be positive")
    private BigDecimal baseFare;

    /**
     * RAC (Reservation Against Cancellation) slots offered by this coach on one journey date.
     * Optional; 0 means the coach offers RAC only through waitlist promotion.
     */
    @Min(value = 0, message = "RAC capacity cannot be negative")
    @Max(value = 50, message = "RAC capacity must be at most 50")
    private Integer racCapacity;
}
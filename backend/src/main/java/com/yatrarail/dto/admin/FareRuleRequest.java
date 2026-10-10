package com.yatrarail.dto.admin;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class FareRuleRequest {
    @NotBlank(message = "Seat class is required")
    private String seatClass;

    @NotNull
    @Positive(message = "Fare per km must be positive")
    @Digits(integer = 6, fraction = 2, message = "Fare per km must have at most 2 decimal places")
    private BigDecimal farePerKm;

    @NotNull
    @Positive(message = "Minimum fare must be positive")
    @Digits(integer = 6, fraction = 2, message = "Minimum fare must have at most 2 decimal places")
    private BigDecimal minimumFare;

    /**
     * Phase 4 — the flat per-booking platform/convenience fee. Zero means "no such charge for this
     * class"; it is a fee and not a percentage, so a class with no fee simply sends 0.
     */
    @NotNull(message = "Platform fee is required (send 0 for none)")
    @DecimalMin(value = "0.0", message = "Platform fee cannot be negative")
    @DecimalMax(value = "500.0", message = "Platform fee must be at most 500")
    @Digits(integer = 3, fraction = 2, message = "Platform fee must have at most 2 decimal places")
    private BigDecimal platformFee = BigDecimal.ZERO;

    /** GST applied to the passenger fare subtotal. The platform fee is not taxed. */
    @NotNull(message = "GST percentage is required (send 0 for none)")
    @DecimalMin(value = "0.0", message = "GST percentage cannot be negative")
    @DecimalMax(value = "40.0", message = "GST percentage must be at most 40")
    @Digits(integer = 2, fraction = 2, message = "GST percentage must have at most 2 decimal places")
    private BigDecimal gstPercent = BigDecimal.ZERO;
}

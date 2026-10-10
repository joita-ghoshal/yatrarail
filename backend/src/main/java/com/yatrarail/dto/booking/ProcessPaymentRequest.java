package com.yatrarail.dto.booking;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class ProcessPaymentRequest {
    @NotBlank(message = "Payment method is required")
    @Pattern(regexp = "(?i)^(UPI|CARD|NETBANKING)$", message = "Payment method must be UPI, CARD or NETBANKING")
    private String method; // UPI, CARD, NETBANKING

    /** For the demo simulator: if false, the mock payment is made to fail. Defaults true. */
    private Boolean simulateSuccess = true;
}
package com.yatrarail.dto.payment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * Phase 5 — a passenger-level cancellation request: which passengers, and why.
 * <p>
 * Sending every passenger id cancels the whole booking and returns the platform fee; sending a strict
 * subset is a partial cancellation and does not. That one rule is why the ids are required rather
 * than optional: an omitted list has to mean something unambiguous, and "everything" already has a
 * dedicated endpoint for it.
 * <p>
 * The ids are validated against <i>this</i> booking server-side, so a passenger cannot smuggle
 * another booking's passenger id in and cancel someone else's seat.
 */
@Data
public class PassengerRefundRequest {

    @NotEmpty(message = "Select at least one passenger to cancel")
    @Size(max = 10, message = "A maximum of 10 passengers can be cancelled at once")
    private List<Long> passengerIds;

    @NotBlank(message = "Cancellation reason is required")
    @Size(max = 255, message = "Cancellation reason must be at most 255 characters")
    private String reason;
}

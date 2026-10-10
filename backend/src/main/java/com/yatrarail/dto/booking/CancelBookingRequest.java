package com.yatrarail.dto.booking;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CancelBookingRequest {
    @NotBlank(message = "Cancellation reason is required")
    @Size(max = 255, message = "Cancellation reason must be at most 255 characters")
    private String reason;
}
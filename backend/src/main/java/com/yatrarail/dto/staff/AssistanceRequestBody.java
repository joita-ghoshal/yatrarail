package com.yatrarail.dto.staff;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Request body for a passenger raising an assistance request. Scoped to one passenger of the
 * booking rather than the booking, so a group can ask for help for exactly the person who needs it.
 */
@Data
public class AssistanceRequestBody {
    @NotNull(message = "Passenger is required")
    private Long passengerId;

    @NotBlank(message = "Category is required")
    private String category;

    @Size(max = 400, message = "Details must be 400 characters or fewer")
    private String details;
}
package com.yatrarail.dto.staff;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Request body for filing an incident against a train on a journey date. */
@Data
public class IncidentRequest {
    @NotNull(message = "Train is required")
    private Long trainId;

    @NotNull(message = "Journey date is required")
    private String journeyDate;

    @NotBlank(message = "Incident type is required")
    private String type;

    @NotBlank(message = "Severity is required")
    private String severity;

    /** Optional: null for a disruption with no time cost, such as a diversion notice. */
    @Min(value = 0, message = "Delay minutes cannot be negative")
    @Max(value = 10080, message = "Delay minutes looks unrealistic (over a week)")
    private Integer delayMinutes;

    @NotBlank(message = "Description is required")
    @Size(max = 300, message = "Description must be 300 characters or fewer")
    private String description;
}
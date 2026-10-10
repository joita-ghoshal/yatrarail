package com.yatrarail.dto.staff;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Request body for updating an incident's lifecycle state. */
@Data
public class IncidentUpdateRequest {
    /** OPEN, ACKNOWLEDGED or RESOLVED. */
    @NotBlank(message = "Status is required")
    private String status;
}
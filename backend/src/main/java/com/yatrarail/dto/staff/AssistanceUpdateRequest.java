package com.yatrarail.dto.staff;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** Request body for a staff member picking up or closing an assistance request. */
@Data
public class AssistanceUpdateRequest {
    /** OPEN, ACKNOWLEDGED or RESOLVED. */
    @NotBlank(message = "Status is required")
    private String status;
}
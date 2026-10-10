package com.yatrarail.dto.auth;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Guest entry into the one-time booking flow. Deliberately minimal: a display name and an optional
 * contact number. No email and no password are accepted - a temporary account has no permanent
 * identity, and taking an email here would squat an address the guest may want to register later.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateTemporaryAccountRequest {

    @Size(min = 2, max = 120, message = "Name must be between 2 and 120 characters")
    private String fullName;

    @Pattern(regexp = "^$|^[0-9]{10,15}$", message = "Phone must be 10-15 digits")
    private String phone;
}

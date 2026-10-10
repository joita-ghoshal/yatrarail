package com.yatrarail.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Turns a live guest session into a permanent passenger account. Bookings made during the guest
 * session stay attached to the same account row, so nothing is copied or re-linked.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConvertTemporaryAccountRequest {

    @NotBlank(message = "Email is required")
    @Email(message = "A valid email is required")
    @Size(max = 150, message = "Email must be at most 150 characters")
    private String email;

    @NotBlank(message = "Password is required")
    @Size(min = 8, max = 72, message = "Password must be 8-72 characters")
    @Pattern(regexp = ".*[A-Za-z].*", message = "Password must contain a letter")
    @Pattern(regexp = ".*[0-9].*", message = "Password must contain a digit")
    private String password;

    /** Optional overrides; the guest-entered values are kept when omitted. */
    @Size(min = 2, max = 120, message = "Name must be between 2 and 120 characters")
    private String fullName;

    @Pattern(regexp = "^$|^[0-9]{10,15}$", message = "Phone must be 10-15 digits")
    private String phone;
}

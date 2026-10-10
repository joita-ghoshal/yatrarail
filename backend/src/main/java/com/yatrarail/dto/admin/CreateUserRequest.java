package com.yatrarail.dto.admin;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * An admin's request to create an account, including a staff account.
 * <p>
 * The first name of the form is a deliberate limitation: an admin can create the account and set the
 * initial password, but the account holder is expected to change it. {@code mustChangePassword} is
 * carried on the response so the UI can say so.
 */
@Data
public class CreateUserRequest {

    @NotBlank(message = "Full name is required")
    @Size(max = 120, message = "Full name must be at most 120 characters")
    private String fullName;

    @NotBlank(message = "Email is required")
    @Email(message = "Email is not valid")
    @Size(max = 160, message = "Email must be at most 160 characters")
    private String email;

    @NotBlank(message = "Phone is required")
    @Pattern(regexp = "^[0-9+\\- ]{6,20}$", message = "Phone is not a valid number")
    private String phone;

    @NotNull(message = "Role is required")
    private String role;

    @NotBlank(message = "Password is required")
    @Size(min = 8, max = 72, message = "Password must be 8 to 72 characters")
    private String password;

    /** Gender, kept consistent with self-registration so a staff account is a complete record. */
    @Pattern(regexp = "(?i)^(MALE|FEMALE|OTHER)$", message = "Gender must be Male, Female or Other")
    private String gender;

    private Integer age;
}

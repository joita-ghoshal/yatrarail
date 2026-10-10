package com.yatrarail.dto.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * An admin's request to edit another account. Every field is optional: only what is sent changes.
 * <p>
 * Role is included here rather than having a separate endpoint, because "make this person a staff
 * member" and "fix their phone number" are the same conversation in practice. The service rejects
 * changes that would strip the last admin.
 */
@Data
public class UpdateUserRequest {

    @Size(max = 120, message = "Full name must be at most 120 characters")
    private String fullName;

    @Pattern(regexp = "^[0-9+\\- ]{6,20}$", message = "Phone is not a valid number")
    private String phone;

    /** {@code PASSENGER}, {@code STAFF} or {@code ADMIN}. Blank means "leave the role alone". */
    @Pattern(regexp = "(?i)^(PASSENGER|STAFF|ADMIN)$", message = "Role must be Passenger, Staff or Admin")
    private String role;

    @Pattern(regexp = "(?i)^(MALE|FEMALE|OTHER)$", message = "Gender must be Male, Female or Other")
    private String gender;

    private Integer age;
}

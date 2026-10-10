package com.yatrarail.dto.auth;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateProfileRequest {
    @Size(max = 120, message = "Full name must be at most 120 characters")
    private String fullName;

    @Pattern(regexp = "^(|[0-9]{10})$", message = "Phone number must be 10 digits")
    private String phone;

    @Size(max = 10, message = "Gender must be at most 10 characters")
    private String gender;

    @Min(value = 1, message = "Age must be at least 1")
    @Max(value = 120, message = "Age must be at most 120")
    private Integer age;
}
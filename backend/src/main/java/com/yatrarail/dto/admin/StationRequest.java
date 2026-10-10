package com.yatrarail.dto.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class StationRequest {
    @NotBlank(message = "Station code is required")
    @Pattern(regexp = "^[A-Z]{1,10}$", message = "Station code must be uppercase letters only")
    private String code;

    @NotBlank(message = "Station name is required")
    @Size(max = 120, message = "Station name must be at most 120 characters")
    private String name;

    @NotBlank(message = "City is required")
    @Size(max = 100, message = "City must be at most 100 characters")
    private String city;

    @NotBlank(message = "State is required")
    @Size(max = 100, message = "State must be at most 100 characters")
    private String state;
}
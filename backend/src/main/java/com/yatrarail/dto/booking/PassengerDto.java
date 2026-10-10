package com.yatrarail.dto.booking;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class PassengerDto {
    @NotBlank(message = "Passenger name is required")
    @Size(max = 120, message = "Passenger name must be at most 120 characters")
    private String fullName;

    @NotNull(message = "Passenger age is required")
    @Min(value = 1, message = "Age must be at least 1")
    @Max(value = 120, message = "Age must be realistic")
    private Integer age;

    @NotBlank(message = "Passenger gender is required")
    @Pattern(regexp = "(?i)^(MALE|FEMALE|OTHER)$",
            message = "Gender must be Male, Female or Other")
    private String gender;

    @Pattern(regexp = "(?i)^(NO_PREFERENCE|LOWER|MIDDLE|UPPER|SIDE_LOWER|SIDE_UPPER|WINDOW|AISLE)$",
            message = "Invalid berth preference")
    private String berthPreference;

    /**
     * The booking quota the passenger is asking for. Blank means {@code GENERAL}.
     * <p>
     * This is a <i>request</i>, not a grant: the backend validates eligibility (age, gender, a
     * declared disability, the role of the account making the booking, the Tatkal window) and then
     * decides which quota the passenger really travels under, because a reserved quota that is
     * full falls back to general. The quota finally used is always reported back on the booking.
     */
    @Pattern(regexp = "(?i)^(GENERAL|TATKAL|SENIOR|LADIES|DIVYANG|STAFF)$",
            message = "Invalid booking quota")
    private String quota;

    /**
     * Self-declaration required to use the DIVYANG quota. Deliberately explicit: without it the
     * backend refuses the request rather than quietly issuing a concessionary ticket.
     */
    private Boolean disabilityCertified;
}

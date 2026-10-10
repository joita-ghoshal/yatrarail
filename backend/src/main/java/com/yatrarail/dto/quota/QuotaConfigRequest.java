package com.yatrarail.dto.quota;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Admin payload that upserts the quota split for one (train, class) pair. */
@Data
public class QuotaConfigRequest {

    @NotNull(message = "Train is required")
    @Positive(message = "Train must be a positive id")
    private Long trainId;

    @NotBlank(message = "Seat class is required")
    @Size(max = 20, message = "Seat class must be at most 20 characters")
    private String seatClass;

    @NotNull(message = "Tatkal seats is required")
    @Min(value = 0, message = "Tatkal seats cannot be negative")
    @Max(value = 200, message = "Tatkal seats must be at most 200")
    private Integer tatkalSeats = 0;

    @NotNull(message = "Senior citizen seats is required")
    @Min(value = 0, message = "Senior citizen seats cannot be negative")
    @Max(value = 200, message = "Senior citizen seats must be at most 200")
    private Integer seniorSeats = 0;

    @NotNull(message = "Ladies seats is required")
    @Min(value = 0, message = "Ladies seats cannot be negative")
    @Max(value = 200, message = "Ladies seats must be at most 200")
    private Integer ladiesSeats = 0;

    @NotNull(message = "Divyang seats is required")
    @Min(value = 0, message = "Divyang seats cannot be negative")
    @Max(value = 200, message = "Divyang seats must be at most 200")
    private Integer divyangSeats = 0;

    @NotNull(message = "Staff seats is required")
    @Min(value = 0, message = "Staff seats cannot be negative")
    @Max(value = 200, message = "Staff seats must be at most 200")
    private Integer staffSeats = 0;

    /** How many days before departure the Tatkal-sim quota may be used. */
    @NotNull(message = "Tatkal window (days) is required")
    @Min(value = 0, message = "Tatkal window cannot be negative")
    @Max(value = 7, message = "Tatkal window must be at most 7 days")
    private Integer tatkalWindowDays = 1;

    private Boolean active = true;
}

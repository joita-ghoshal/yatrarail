package com.yatrarail.dto.admin;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalTime;
import java.util.List;

@Data
public class TrainRequest {
    @NotBlank(message = "Train number is required")
    @Pattern(regexp = "^[0-9]{4,6}$", message = "Train number must be 4-6 digits")
    private String trainNumber;

    @NotBlank(message = "Train name is required")
    @Size(max = 120, message = "Train name must be at most 120 characters")
    private String name;

    @NotBlank(message = "Train type is required")
    @Size(max = 20, message = "Train type must be at most 20 characters")
    private String type;

    @NotNull(message = "Source station is required")
    @Positive(message = "Source station must be a positive id")
    private Long sourceStationId;

    @NotNull(message = "Destination station is required")
    @Positive(message = "Destination station must be a positive id")
    private Long destinationStationId;

    @NotNull(message = "Departure time is required")
    private LocalTime departureTime;

    @NotNull(message = "Arrival time is required")
    private LocalTime arrivalTime;

    @NotNull(message = "Duration is required")
    @Positive(message = "Duration must be positive")
    @Max(value = 2880, message = "Duration must be at most 2880 minutes")
    private Integer durationMinutes;

    @NotBlank(message = "Runs-on days are required")
    @Pattern(regexp = "^(MON|TUE|WED|THU|FRI|SAT|SUN)(,(MON|TUE|WED|THU|FRI|SAT|SUN))*$",
            message = "Runs-on must be a comma separated list of MON,TUE,WED,THU,FRI,SAT,SUN")
    private String runsOn;

    private List<@Valid RouteStationRequest> route;
}
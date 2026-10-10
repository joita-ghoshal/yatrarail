package com.yatrarail.dto.admin;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.time.LocalTime;

@Data
public class RouteStationRequest {
    @NotNull(message = "Route station is required")
    @Positive(message = "Route station must be a positive id")
    private Long stationId;

    @NotNull(message = "Sequence number is required")
    @Min(1)
    private Integer sequenceNumber;

    private LocalTime arrivalTime;
    private LocalTime departureTime;

    @Min(0)
    @Max(1440)
    private Integer haltMinutes;

    @NotNull(message = "Distance is required")
    @Min(0)
    private Integer distanceFromSourceKm;
}
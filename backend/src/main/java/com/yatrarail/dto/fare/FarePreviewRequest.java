package com.yatrarail.dto.fare;

import com.yatrarail.dto.booking.PassengerDto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/**
 * Admin-only fare calculator. It runs the real production pricing code against a hypothetical
 * journey, so an administrator can see exactly what a fare rule or quota change does to a real
 * price without creating a booking. It never touches inventory.
 */
@Data
public class FarePreviewRequest {

    @NotNull(message = "Train is required")
    @Positive(message = "Train must be a positive id")
    private Long trainId;

    @NotNull(message = "Coach/class is required")
    @Positive(message = "Coach must be a positive id")
    private Long coachId;

    @NotNull(message = "Journey date is required")
    private LocalDate journeyDate;

    @NotNull(message = "Source station is required")
    @Positive(message = "Source station must be a positive id")
    private Long sourceStationId;

    @NotNull(message = "Destination station is required")
    @Positive(message = "Destination station must be a positive id")
    private Long destinationStationId;

    @NotNull(message = "At least one passenger is required")
    @Size(min = 1, max = 10, message = "Between 1 and 10 passengers are required")
    private List<@Valid PassengerDto> passengers;
}

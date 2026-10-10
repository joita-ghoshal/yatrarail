package com.yatrarail.dto.booking;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

@Data
public class CreateBookingRequest {
    @NotNull(message = "Train is required")
    @Positive(message = "Train must be a positive id")
    private Long trainId;

    @NotNull(message = "Coach/class is required")
    @Positive(message = "Coach must be a positive id")
    private Long coachId;

    @NotNull(message = "Source station is required")
    @Positive(message = "Source station must be a positive id")
    private Long sourceStationId;

    @NotNull(message = "Destination station is required")
    @Positive(message = "Destination station must be a positive id")
    private Long destinationStationId;

    @NotNull(message = "Journey date is required")
    private LocalDate journeyDate;

    @NotEmpty(message = "At least one passenger is required")
    @Size(max = 6, message = "A maximum of 6 passengers can be booked at once")
    private List<@Valid PassengerDto> passengers;

    /**
     * Whether the passenger accepts a RAC (Reservation Against Cancellation) entry when no confirmed
     * seat is free. When false, surplus passengers go straight to the waitlist.
     */
    private Boolean allowRac = true;
}

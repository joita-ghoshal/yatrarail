package com.yatrarail.dto.booking;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/**
 * A read-only preview of what a booking would cost and how much inventory is available.
 * <p>
 * The frontend uses this instead of multiplying a per-class fare by the passenger count itself, so
 * the number the passenger reviews is always the number the server will charge.
 */
@Data
public class BookingQuoteRequest {
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

    @NotNull(message = "Passenger count is required")
    @Min(value = 1, message = "At least one passenger is required")
    @Max(value = 10, message = "Too many passengers in one booking")
    private Integer passengers = 1;

    /** Whether the passenger is willing to travel on RAC when no seat is free. Defaults to true. */
    private Boolean allowRac = true;

    /**
     * Optional full passenger list. When present the quote resolves each passenger's quota
     * individually and returns the same itemised fare breakdown the booking will store; when absent
     * the quote falls back to {@link #passengers} anonymous general-quota passengers, which keeps
     * simple callers (and the public search path) working unchanged.
     */
    @Size(max = 10, message = "Too many passengers in one booking")
    private List<@Valid PassengerDto> passengerDetails;
}

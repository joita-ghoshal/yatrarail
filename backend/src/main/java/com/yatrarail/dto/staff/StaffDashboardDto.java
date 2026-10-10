package com.yatrarail.dto.staff;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The one number set the staff dashboard opens on. Grouped by journey date rather than a single
 * flat total so a conductor sees "today's train is full" without mentally subtracting yesterday.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StaffDashboardDto {
    private String journeyDate;

    /** Active trains running on this date. */
    private long activeTrains;

    /** Bookings in a travelling state (CONFIRMED / RAC / WAITLISTED) on this date. */
    private long totalBookings;
    private long confirmedBookings;
    private long racBookings;
    private long waitlistedBookings;

    /** Passenger-level tallies, including gate check-in progress. */
    private long totalPassengers;
    private long boardedPassengers;
    private long notBoardedPassengers;
    private long noShowPassengers;

    /** Incidents on this date, split by whether somebody still owns them. */
    private long openIncidents;
    private long resolvedIncidents;

    /** Assistance requests on this date, split the same way. */
    private long openAssistanceRequests;
    private long resolvedAssistanceRequests;
}
package com.yatrarail.dto.booking;

import com.yatrarail.dto.fare.FareBreakdownDto;
import com.yatrarail.dto.quota.QuotaAvailabilityDto;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BookingQuoteDto {
    private Long trainId;
    private String trainNumber;
    private String trainName;
    private Long coachId;
    private String coachCode;
    private String seatClass;
    private String seatClassLabel;
    private Long sourceStationId;
    private String sourceStationName;
    private Long destinationStationId;
    private String destinationStationName;
    private LocalDate journeyDate;
    /** Distance of the requested segment in km (not the train's full route length). */
    private Integer distanceKm;
    private Integer passengers;
    private BigDecimal farePerPassenger;
    private BigDecimal totalFare;
    private int totalSeats;
    private int availableSeats;
    private int racSeatsAvailable;
    /** AVAILABLE, RAC_AVAILABLE or WAITLIST. */
    private String status;
    /** Confirmed seats / RAC entries / waitlist entries this request would consume. */
    private int wouldConfirm;
    private int wouldRac;
    private int wouldWaitlist;
    /**
     * The exact itemised price this request would create: the three fare candidates, one line per
     * passenger with the quota actually available to them, the platform fee and the GST. Returned
     * by the quote so the number the passenger reviews is the number the booking will store.
     */
    private FareBreakdownDto fareBreakdown;
    /** Live per-quota availability for this class on this journey date, in quota order. */
    private List<QuotaAvailabilityDto> quotaAvailability;
}

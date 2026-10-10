package com.yatrarail.dto.ticket;

import com.yatrarail.dto.booking.PassengerResultDto;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * Phase 5 — the outcome of scanning a ticket QR code.
 * <p>
 * <b>A verification always returns a verdict, never an error.</b> A scanner at a gate needs to be
 * able to distinguish "this is a forged ticket" from "this is a real ticket for a journey that has
 * been cancelled" from "I could not read that", and each of those needs a different action at the
 * counter. So the failures are modelled as a {@link #outcome} value with a message, not as HTTP
 * error codes. The only thing that will 4xx is a request the caller has no business making.
 * <p>
 * Nothing here is trusted from the token. The payload's signature proves the server issued the
 * string; every fact below is read from the live database afterwards, so a cancellation that
 * happened ten minutes after the ticket was printed is reflected immediately.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TicketVerificationResultDto {

    public static final String OUTCOME_VALID = "VALID";
    public static final String OUTCOME_CANCELLED = "CANCELLED";
    public static final String OUTCOME_EXPIRED = "EXPIRED";
    public static final String OUTCOME_UNPAID = "UNPAID";
    public static final String OUTCOME_NOT_JOURNEY_DAY = "NOT_JOURNEY_DAY";
    public static final String OUTCOME_FORGED = "FORGED";
    public static final String OUTCOME_STALE = "QR_STALE";
    public static final String OUTCOME_NOT_YET_ISSUED = "NOT_YET_ISSUED";
    public static final String OUTCOME_MALFORMED = "MALFORMED";
    public static final String OUTCOME_UNKNOWN_BOOKING = "UNKNOWN_BOOKING";

    /** One of the {@code OUTCOME_*} constants. */
    private String outcome;

    /** True only for {@link #OUTCOME_VALID}. */
    private Boolean valid;

    /** What the reader should do, and what the problem is if there is one. */
    private String message;

    /** Null unless the payload parsed and named a booking. */
    private String bookingReference;

    // ---- present only when the payload was a genuine YatraRail ticket ----
    private Long bookingId;
    private String trainName;
    private String trainNumber;
    private String sourceStationName;
    private String destinationStationName;
    private LocalDate journeyDate;
    private LocalTime departureTime;
    private LocalTime arrivalTime;
    private String seatClass;
    private String coachCode;
    private String bookingStatus;
    private String bookedByName;
    private List<PassengerResultDto> passengers;

    /**
     * Total paid on the booking, so a gate can see the value being carried. Null when the booking
     * was never paid for, which is itself a finding.
     */
    private java.math.BigDecimal amountPaid;

    /** Refunds already issued against the booking, so a partially-refunded ticket is obvious. */
    private java.math.BigDecimal refundedAmount;

    private LocalDateTime verifiedAt;

    /** ISO-8601 instant the QR was issued, from the signed payload rather than from a lookup. */
    private LocalDateTime qrIssuedAt;
}

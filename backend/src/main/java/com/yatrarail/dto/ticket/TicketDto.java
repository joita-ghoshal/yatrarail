package com.yatrarail.dto.ticket;

import com.yatrarail.dto.booking.PassengerResultDto;
import com.yatrarail.dto.fare.FareBreakdownDto;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * Phase 5 — everything a printable ticket needs, and nothing it does not.
 * <p>
 * Deliberately assembled from the <i>stored</i> booking snapshot rather than re-priced from the
 * live tariff, for the same reason Phase 4 rebuilt the fare breakdown from the snapshot: a ticket
 * printed next year must say what the passenger actually paid, not what today's rules would charge.
 * <p>
 * The ticket also carries the refund policy it was issued under. Printing the terms on the ticket is
 * the difference between a passenger discovering the cancellation charge at the counter and
 * knowing it before they commit.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TicketDto {

    private Long bookingId;

    /** YatraRail reference. NOT an official Indian Railways PNR. */
    private String bookingReference;

    // ---- journey ----
    private String trainName;
    private String trainNumber;
    private String sourceStationName;
    private String destinationStationName;
    private LocalDate journeyDate;
    /** Scheduled departure from the boarding station on this route. */
    private LocalTime departureTime;
    /** Scheduled arrival at the alighting station on this route. */
    private LocalTime arrivalTime;
    private Integer distanceKm;
    private String seatClass;

    /** The booking's own status (CONFIRMED / RAC / WAITLISTED / CANCELLED / EXPIRED / ...). */
    private String bookingStatus;

    /**
     * True only while the ticket is actually usable: paid, not cancelled, not expired, and the
     * journey has not already departed. A ticket for a cancelled booking is still printable -
     * people need the record - but it is marked as not valid for travel.
     */
    private Boolean validForTravel;

    /** Why the ticket is or is not valid for travel, in words a passenger can act on. */
    private String validityMessage;

    // ---- who and what ----
    private String bookedByName;
    private List<PassengerResultDto> passengers;

    /**
     * The itemised fare as stored at booking time, so the ticket can explain its own price after the
     * tariff has moved on.
     */
    private FareBreakdownDto fareBreakdown;

    // ---- QR ----
    /**
     * The signed QR payload, rendered by the browser. Opaque on purpose: the signature is over the
     * booking reference, so the string carries no fare or passenger data for a shoulder-surfer to
     * photograph.
     */
    private String qrPayload;

    private LocalDateTime qrIssuedAt;

    /**
     * When the QR stops being accepted as fresh. Informational: verification always re-reads the
     * live booking, so an expired QR reports "look this up" rather than "this is a forgery".
     */
    private LocalDateTime qrValidUntil;

    // ---- refund terms, as printed on the ticket ----
    private List<RefundPolicyRowDto> refundPolicy;

    /** False once the journey has departed or the booking can no longer be cancelled. */
    private Boolean cancellable;

    private String cancellationMessage;
}

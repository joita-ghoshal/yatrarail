package com.yatrarail.service;

import com.yatrarail.config.TicketProperties;
import com.yatrarail.dto.ticket.TicketDto;
import com.yatrarail.dto.ticket.TicketVerificationResultDto;
import com.yatrarail.entity.*;
import com.yatrarail.exception.ResourceNotFoundException;
import com.yatrarail.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Optional;

/**
 * Phase 5 — the printable ticket and its QR code.
 * <p>
 * <b>The signature is the point.</b> A booking reference is eight characters from a 32-symbol
 * alphabet, which is not enough entropy to treat as a bearer token: a script could brute-force
 * plausible references and print a convincing ticket. So the QR payload carries the reference
 * <i>and</i> an HMAC-SHA256 over it, keyed with a secret that only exists on the server. A forged
 * code fails the signature check; a genuine code still only proves what it was issued for, which is
 * why every fact reported by {@link #verify} is then read from the live database rather than
 * decoded from the string. A ticket cancelled a minute ago is reported cancelled, and the printed
 * bitmap's claim to the contrary is ignored.
 * <p>
 * <b>Nothing sensitive is encoded.</b> The payload is a reference and a signature, so photographing
 * the QR discloses no fare, no passenger name and no seat — the details a gate needs come from the
 * authenticated lookup.
 */
@Service
@RequiredArgsConstructor
public class TicketService {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final char SEPARATOR = '.';

    private final TicketProperties ticketProperties;
    private final BookingRepository bookingRepository;
    private final PaymentRepository paymentRepository;
    private final CancellationRepository cancellationRepository;
    private final RouteStationRepository routeStationRepository;
    private final FareCalculationService fareCalculationService;
    private final RefundService refundService;
    private final PassengerMapper passengerMapper;

    // ------------------------------------------------------------------ ticket

    /**
     * Builds the printable ticket for a booking the caller owns.
     * <p>
     * The stored fare snapshot is reused, never re-priced, so the ticket says what the passenger
     * actually paid even after the tariff changes.
     */
    @Transactional(readOnly = true)
    public TicketDto getTicket(Long userId, Long bookingId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found"));
        if (!booking.getUser().getId().equals(userId)) {
            // Same 404 as a genuinely missing booking: never confirm that somebody else's booking exists.
            throw new ResourceNotFoundException("Booking not found");
        }
        return toTicket(booking);
    }

    private TicketDto toTicket(Booking booking) {
        LocalDateTime issuedAt = LocalDateTime.now();
        RefundService.CancellationCheck cancellable = refundService.checkCancellable(booking);
        Validity validity = validityOf(booking);

        return TicketDto.builder()
                .bookingId(booking.getId())
                .bookingReference(booking.getBookingReference())
                .trainName(booking.getTrain().getName())
                .trainNumber(booking.getTrain().getTrainNumber())
                .sourceStationName(booking.getSourceStation().getName())
                .destinationStationName(booking.getDestinationStation().getName())
                .journeyDate(booking.getJourneyDate())
                .departureTime(departureFrom(booking))
                .arrivalTime(arrivalAt(booking))
                .distanceKm(booking.getDistanceKm())
                .seatClass(booking.getCoach().getSeatClass().getLabel())
                .bookingStatus(booking.getStatus().name())
                .validForTravel(validity.valid())
                .validityMessage(validity.message())
                .bookedByName(booking.getUser().getFullName())
                .passengers(passengerMapper.describe(booking))
                .fareBreakdown(fareCalculationService.rebuildFromSnapshot(booking))
                .qrPayload(sign(booking.getBookingReference(), issuedAt))
                .qrIssuedAt(issuedAt)
                .qrValidUntil(issuedAt.plusDays(ticketProperties.getValidDays()))
                .refundPolicy(refundService.policyRows())
                .cancellable(cancellable.allowed())
                .cancellationMessage(cancellable.allowed()
                        ? "Cancel before " + booking.getJourneyDate() + " to receive a refund under the tiers below."
                        : cancellable.message())
                .build();
    }

    // ------------------------------------------------------------------ verification

    /**
     * Checks a scanned payload and reports what the live booking actually says.
     * <p>
     * Every failure is an outcome with a message rather than an exception: a gate needs to tell
     * "forged" apart from "genuine but cancelled" apart from "not a YatraRail ticket", and each
     * needs a different action. The order matters and is deliberate:
     * <ol>
     *   <li>shape - is this a payload we could have issued at all? A scanner aimed at a restaurant
     *       menu must get an honest "unrecognised", not a signature failure;</li>
     *   <li>signature - was it issued by <i>this</i> deployment? A staging ticket is not a
     *       production ticket;</li>
     *   <li>time - was it issued at all yet, and is the printed copy still within its window;</li>
     *   <li>the live booking - which is the only authority on whether the passenger may travel.</li>
     * </ol>
     *
     * @param canReadAnyBooking true for STAFF and ADMIN; a passenger may only verify their own.
     */
    @Transactional(readOnly = true)
    public TicketVerificationResultDto verify(Long callerId, boolean canReadAnyBooking, String rawPayload) {
        LocalDateTime verifiedAt = LocalDateTime.now();
        ParsedPayload parsed = parse(rawPayload);

        if (parsed.failure() != null) {
            return verdict(TicketVerificationResultDto.OUTCOME_MALFORMED, false, parsed.failure(), null, null,
                    verifiedAt);
        }
        if (!constantTimeEquals(parsed.signature(), signatureFor(parsed.signingInput()))) {
            // Deliberately the same shape of answer as a bad prefix: a caller must not be able to
            // tell a forged reference from a real one by timing the response.
            return verdict(TicketVerificationResultDto.OUTCOME_FORGED, false,
                    "This ticket's signature does not match. It was not issued by YatraRail.", null, null,
                    verifiedAt);
        }

        LocalDate issuedDay = LocalDate.ofEpochDay(parsed.issuedOnEpochDay());
        LocalDate today = verifiedAt.toLocalDate();
        if (issuedDay.isAfter(today)) {
            return verdict(TicketVerificationResultDto.OUTCOME_NOT_YET_ISSUED, false,
                    "This ticket is dated " + issuedDay + ", which is in the future. It has not been issued yet.",
                    parsed.bookingReference(), null, verifiedAt);
        }

        Optional<Booking> found = bookingRepository.findByBookingReference(parsed.bookingReference());
        if (found.isEmpty()) {
            return verdict(TicketVerificationResultDto.OUTCOME_UNKNOWN_BOOKING, false,
                    "This ticket's reference " + parsed.bookingReference() + " is not on the YatraRail system.",
                    parsed.bookingReference(), null, verifiedAt);
        }
        Booking booking = found.get();

        if (!canReadAnyBooking && !booking.getUser().getId().equals(callerId)) {
            // A passenger scanning a stranger's ticket is told the same thing as when the booking is
            // simply absent, so the endpoint cannot be used to confirm that a reference is real.
            return verdict(TicketVerificationResultDto.OUTCOME_UNKNOWN_BOOKING, false,
                    "This ticket's reference " + parsed.bookingReference() + " is not on the YatraRail system.",
                    parsed.bookingReference(), null, verifiedAt);
        }

        Payment payment = paymentRepository.findByBookingId(booking.getId()).orElse(null);
        BigDecimal refunded = cancellationRepository.findByBookingIdOrderByCancelledAtAsc(booking.getId())
                .stream()
                .map(Cancellation::getRefundAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        TicketVerificationResultDto.TicketVerificationResultDtoBuilder result =
                TicketVerificationResultDto.builder()
                        .bookingReference(booking.getBookingReference())
                        .bookingId(booking.getId())
                        .trainName(booking.getTrain().getName())
                        .trainNumber(booking.getTrain().getTrainNumber())
                        .sourceStationName(booking.getSourceStation().getName())
                        .destinationStationName(booking.getDestinationStation().getName())
                        .journeyDate(booking.getJourneyDate())
                        .departureTime(departureFrom(booking))
                        .arrivalTime(arrivalAt(booking))
                        .seatClass(booking.getCoach().getSeatClass().getLabel())
                        .coachCode(booking.getCoach().getCoachCode())
                        .bookingStatus(booking.getStatus().name())
                        .bookedByName(booking.getUser().getFullName())
                        .passengers(passengerMapper.describe(booking))
                        .amountPaid(payment == null ? null : payment.getAmount())
                        .refundedAmount(refunded)
                        .qrIssuedAt(LocalDateTime.of(issuedDay, java.time.LocalTime.MIDNIGHT))
                        .verifiedAt(verifiedAt);

        return switch (booking.getStatus()) {
            case CANCELLED -> result
                    .outcome(TicketVerificationResultDto.OUTCOME_CANCELLED).valid(false)
                    .message("Genuine ticket, but it was cancelled. " + refunded + " has been refunded.")
                    .build();
            case EXPIRED -> result
                    .outcome(TicketVerificationResultDto.OUTCOME_EXPIRED).valid(false)
                    .message("Genuine ticket, but the booking expired before payment was completed.")
                    .build();
            case PAYMENT_PENDING, PAYMENT_FAILED -> result
                    .outcome(TicketVerificationResultDto.OUTCOME_UNPAID).valid(false)
                    .message("Genuine ticket, but this booking was never paid for.")
                    .build();
            default -> {
                // Signed, current, and the booking is in a travel-capable state. The remaining
                // question is whether today is the day, and whether the printed copy is fresh.
                if (!today.equals(booking.getJourneyDate())) {
                    yield result
                            .outcome(TicketVerificationResultDto.OUTCOME_NOT_JOURNEY_DAY).valid(false)
                            .message("Genuine ticket, but it is not valid today. The journey is on "
                                    + booking.getJourneyDate() + ".")
                            .build();
                }
                long ageInDays = daysBetween(issuedDay, today);
                if (ageInDays > ticketProperties.getValidDays()) {
                    yield result
                            .outcome(TicketVerificationResultDto.OUTCOME_STALE).valid(false)
                            .message("Genuine ticket, but this printed copy is " + ageInDays
                                    + " days old, beyond the " + ticketProperties.getValidDays()
                                    + "-day window. Ask the passenger to re-print their ticket.")
                            .build();
                }
                yield result
                        .outcome(TicketVerificationResultDto.OUTCOME_VALID).valid(true)
                        .message("Valid ticket for " + booking.getJourneyDate() + ".")
                        .build();
            }
        };
    }

    // ------------------------------------------------------------------ signing

    /** Builds the QR payload for a booking reference. */
    public String sign(String bookingReference, LocalDateTime issuedAt) {
        long epochDay = issuedAt.toLocalDate().toEpochDay();
        String input = signingInput(bookingReference, epochDay);
        return ticketProperties.getPayloadPrefix() + SEPARATOR
                + ticketProperties.getPayloadVersion() + SEPARATOR
                + ticketProperties.getIssuer() + SEPARATOR
                + bookingReference + SEPARATOR
                + epochDay + SEPARATOR
                + signatureFor(input);
    }

    private String signingInput(String bookingReference, long epochDay) {
        return ticketProperties.getIssuer() + SEPARATOR
                + ticketProperties.getPayloadVersion() + SEPARATOR
                + bookingReference + SEPARATOR
                + epochDay;
    }

    private String signatureFor(String signingInput) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(
                    ticketProperties.getSigningSecret().getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            byte[] digest = mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception ex) {
            // A JCE failure here is a JVM misconfiguration, not something a caller can cause; failing
            // loudly beats quietly issuing tickets that can never be verified.
            throw new IllegalStateException("Unable to sign the ticket payload", ex);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private record ParsedPayload(String bookingReference, long issuedOnEpochDay, String signature,
                                 String signingInput, String failure) {
        static ParsedPayload ok(String bookingReference, long epochDay, String signature, String input) {
            return new ParsedPayload(bookingReference, epochDay, signature, input, null);
        }

        static ParsedPayload malformed(String failure) {
            return new ParsedPayload(null, 0, null, null, failure);
        }
    }

    /**
     * Splits and shape-checks a payload. The expected field count is fixed, so a truncated or
     * hand-edited string is rejected before any cryptographic work is attempted.
     */
    private ParsedPayload parse(String rawPayload) {
        if (rawPayload == null || rawPayload.isBlank()) {
            return ParsedPayload.malformed("No ticket payload was supplied");
        }
        String[] parts = rawPayload.trim().split("\\" + SEPARATOR, -1);
        if (parts.length != 6) {
            return ParsedPayload.malformed("The scanned code is not in the expected YatraRail ticket format");
        }
        if (!ticketProperties.getPayloadPrefix().equals(parts[0])) {
            return ParsedPayload.malformed("The scanned code is not a YatraRail ticket");
        }
        if (!ticketProperties.getPayloadVersion().equals(parts[1])) {
            return ParsedPayload.malformed("This ticket was issued in an unsupported format version");
        }
        if (!ticketProperties.getIssuer().equals(parts[2])) {
            // A legitimately signed ticket from another YatraRail deployment - typically a staging
            // ticket brought to production. Saying so is more useful than calling it a forgery.
            return ParsedPayload.malformed("This ticket was issued by a different YatraRail deployment ("
                    + parts[2] + ")");
        }
        long epochDay;
        try {
            epochDay = Long.parseLong(parts[4]);
        } catch (NumberFormatException ex) {
            return ParsedPayload.malformed("The scanned ticket has a corrupt issue date");
        }
        if (parts[3].isBlank() || parts[5].isBlank()) {
            return ParsedPayload.malformed("The scanned ticket is incomplete");
        }
        return ParsedPayload.ok(parts[3], epochDay, parts[5], signingInput(parts[3], epochDay));
    }

    // ------------------------------------------------------------------ helpers

    private static long daysBetween(LocalDate from, LocalDate to) {
        return ChronoUnit.DAYS.between(from, to);
    }

    private static TicketVerificationResultDto verdict(String outcome, boolean valid, String message,
                                                       String bookingReference, Long bookingId,
                                                       LocalDateTime verifiedAt) {
        return TicketVerificationResultDto.builder()
                .outcome(outcome)
                .valid(valid)
                .message(message)
                .bookingReference(bookingReference)
                .bookingId(bookingId)
                .verifiedAt(verifiedAt)
                .build();
    }

    /** Scheduled departure from the boarding station, or the train's own time if the route lacks it. */
    private LocalTime departureFrom(Booking booking) {
        return routeStationRepository
                .findByTrainIdAndStationId(booking.getTrain().getId(), booking.getSourceStation().getId())
                .map(RouteStation::getDepartureTime)
                .orElseGet(() -> booking.getTrain().getDepartureTime());
    }

    /** Scheduled arrival at the alighting station, or the train's own time if the route lacks it. */
    private LocalTime arrivalAt(Booking booking) {
        return routeStationRepository
                .findByTrainIdAndStationId(booking.getTrain().getId(), booking.getDestinationStation().getId())
                .map(RouteStation::getArrivalTime)
                .orElseGet(() -> booking.getTrain().getArrivalTime());
    }

    private record Validity(boolean valid, String message) {
    }

    /**
     * Whether this ticket may still be used for travel, in words. Purely a function of the booking's
     * live state, which is the whole point of verifying against the database rather than the printout.
     */
    private Validity validityOf(Booking booking) {
        return switch (booking.getStatus()) {
            case CANCELLED -> new Validity(false, "This booking was cancelled");
            case EXPIRED -> new Validity(false, "This booking expired before payment was completed");
            case PAYMENT_PENDING, PAYMENT_FAILED ->
                    new Validity(false, "This booking is not paid for yet");
            default -> new Validity(true, "Valid for travel on " + booking.getJourneyDate());
        };
    }
}

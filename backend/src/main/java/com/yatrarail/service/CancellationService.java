package com.yatrarail.service;

import com.yatrarail.dto.payment.PassengerRefundLineDto;
import com.yatrarail.dto.payment.PassengerRefundRequest;
import com.yatrarail.dto.payment.RefundPreviewDto;
import com.yatrarail.dto.payment.RefundRecordDto;
import com.yatrarail.entity.*;
import com.yatrarail.exception.BadRequestException;
import com.yatrarail.exception.ConflictException;
import com.yatrarail.exception.ResourceNotFoundException;
import com.yatrarail.repository.BookingRepository;
import com.yatrarail.repository.CancellationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Phase 5 — passenger-level cancellation.
 * <p>
 * Phases 2-4 could only cancel a whole booking: the refund row was one-to-one with the booking, so a
 * passenger who needed to drop one traveller out of three had to cancel all three and rebook. This
 * service makes the subset the unit of cancellation.
 * <p>
 * <b>What is deliberately not delegated.</b> The whole-booking {@code POST /cancel} path stays in
 * {@code BookingService} rather than being routed through here. It is the endpoint every existing
 * client and test already depends on, it returns the booking DTO, and re-implementing it here would
 * mean duplicating that mapping for no benefit. This service is purely additive.
 * <p>
 * <b>Ordering of the checks matters for correctness, not just for the error message.</b> Ownership
 * is checked first, so a passenger probing ids gets the same 404 as for a booking that does not
 * exist. Already-cancelled passengers are then excluded, which means the refund arithmetic only ever
 * runs against seats that are still held — cancelling a seat twice would otherwise pay twice, and
 * the database's unique constraint would turn that into a 500 rather than a clean rejection.
 */
@Service
@RequiredArgsConstructor
public class CancellationService {

    private static final BigDecimal ZERO = new BigDecimal("0.00");

    private final BookingRepository bookingRepository;
    private final CancellationRepository cancellationRepository;
    private final SeatAllocationService seatAllocationService;
    private final RefundService refundService;
    private final NotificationService notificationService;

    // ------------------------------------------------------------------ preview

    /**
     * What the passenger would get back for cancelling the given passengers, right now. Read-only:
     * no seat moves, nothing is written, and it is safe to call on every render of a modal.
     */
    @Transactional(readOnly = true)
    public RefundPreviewDto preview(Long userId, Long bookingId, List<Long> passengerIds) {
        Booking booking = ownedBooking(userId, bookingId);
        List<BookingPassenger> selected = resolveSelection(booking, passengerIds, false);
        return refundService.preview(booking, selected);
    }

    // ------------------------------------------------------------------ cancel

    /**
     * Cancels the selected passengers and issues their refunds.
     *
     * @return the refund records just written, one per cancellation event
     */
    @Transactional
    public List<RefundRecordDto> cancelPassengers(Long userId, Long bookingId, PassengerRefundRequest request) {
        Booking booking = ownedBooking(userId, bookingId);

        // Resolve against the live rows and reject the request if the selection does not match what
        // the passenger asked for, rather than silently cancelling fewer people than they requested.
        List<BookingPassenger> selected = resolveSelection(booking, request.getPassengerIds(), true);

        RefundService.RefundComputation computation = refundService.compute(booking, selected);

        // One ledger row per cancelled passenger for a partial cancellation, and a single row for the
        // whole booking when everything is cancelled. Writing one row per passenger even for a full
        // cancellation would be a defensible alternative, but it would lose the fact that the
        // platform fee came back, and the passenger's refund history reads better as one entry for
        // "all passengers".
        List<Cancellation> written = new ArrayList<>();
        if (computation.scope() == CancellationScope.FULL) {
            written.add(writeCancellation(booking, null, request.getReason(), computation));
        } else {
            // The rows carry each passenger's own line, not the aggregate. Writing the aggregate on
            // every row would make the ledger claim the booking was refunded once per person, and the
            // database's fare + gst + fee == refund CHECK would reject it.
            for (PassengerRefundLineDto line : computation.lines()) {
                written.add(writePartialCancellation(booking, line, request.getReason(), computation));
            }
        }
        cancellationRepository.saveAll(written);

        seatAllocationService.releasePassengers(booking, computation.passengers(), PassengerStatus.CANCELLED);

        boolean allCancelled = booking.getPassengers().stream()
                .allMatch(p -> p.getPassengerStatus() == PassengerStatus.CANCELLED);
        if (allCancelled) {
            booking.setStatus(BookingStatus.CANCELLED);
        } else {
            // A partial cancellation leaves a real booking behind, so the status has to reflect the
            // passengers who are still travelling rather than being left stale.
            seatAllocationService.refreshBookingStatus(booking);
        }
        booking = bookingRepository.save(booking);

        String scopeText = computation.scope() == CancellationScope.FULL
                ? "the whole booking"
                : computation.passengers().size() + " passenger(s)";
        notificationService.notify(booking.getUser(), NotificationType.CANCELLATION,
                computation.scope() == CancellationScope.FULL
                        ? "Booking cancelled"
                        : "Passengers cancelled",
                "Cancelled " + scopeText + " of booking " + booking.getBookingReference() + ". "
                        + "A simulated demo refund of INR " + computation.totalRefund()
                        + " has been issued at the '" + computation.tier().getLabel() + "' tier.");

        return written.stream().map(this::toRecord).toList();
    }

    // ------------------------------------------------------------------ internals

    private Booking ownedBooking(Long userId, Long bookingId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found"));
        if (!booking.getUser().getId().equals(userId)) {
            throw new ResourceNotFoundException("Booking not found");
        }
        return booking;
    }

    /**
     * Maps the requested ids onto this booking's passengers.
     *
     * @param strict when true, an unknown or already-cancelled id is an error; when false (a preview)
     *               they are simply skipped, so a stale checkbox in the UI degrades to a smaller
     *               honest quote rather than to a failure.
     */
    private List<BookingPassenger> resolveSelection(Booking booking, List<Long> passengerIds, boolean strict) {
        if (passengerIds == null || passengerIds.isEmpty()) {
            if (strict) {
                throw new ConflictException("Select at least one passenger to cancel");
            }
            // A preview with nothing selected quotes the whole booking, which is the natural default
            // for a "cancel this booking" button that has not been touched yet.
            return booking.getPassengers();
        }

        Map<Long, BookingPassenger> byId = booking.getPassengers().stream()
                .collect(Collectors.toMap(BookingPassenger::getId, Function.identity()));
        List<BookingPassenger> selected = new ArrayList<>();
        for (Long id : passengerIds) {
            BookingPassenger passenger = byId.get(id);
            if (passenger == null) {
                // Never confirm that a passenger id belongs to some other booking.
                if (strict) {
                    throw new ResourceNotFoundException(
                            "Passenger " + id + " is not part of this booking");
                }
                continue;
            }
            if (cancellationRepository.existsByBookingPassengerId(passenger.getId())) {
                if (strict) {
                    throw new ConflictException(passenger.getFullName() + " has already been cancelled");
                }
                continue;
            }
            if (strict && !RefundService.isLivePassenger(passenger)) {
                throw new ConflictException(passenger.getFullName() + " is no longer cancellable");
            }
            selected.add(passenger);
        }
        return selected;
    }

    private Cancellation writeCancellation(Booking booking, BookingPassenger passenger, String reason,
                                           RefundService.RefundComputation computation) {
        return baseRow(booking, passenger, reason, computation)
                .refundAmount(computation.totalRefund())
                .fareRefunded(computation.fareRefunded())
                .gstRefunded(computation.gstRefunded())
                .platformFeeRefunded(computation.platformFeeRefunded())
                .cancellationCharge(computation.charge())
                .build();
    }

    /**
     * A partial-cancellation row: one passenger's share of the tiered refund, with no platform fee
     * because the booking itself survives. The {@code CHECK} on the table requires
     * {@code fare + gst + fee == refund}, and the lines are built to sum exactly to the aggregate
     * quoted in the preview, so the rows always add up to the total the passenger was promised.
     */
    private Cancellation writePartialCancellation(Booking booking, PassengerRefundLineDto line, String reason,
                                                  RefundService.RefundComputation computation) {
        BookingPassenger passenger = computation.passengers().stream()
                .filter(p -> p.getId().equals(line.getBookingPassengerId()))
                .findFirst()
                .orElseThrow(() -> new BadRequestException(
                        "Passenger " + line.getBookingPassengerId() + " is no longer part of this booking"));
        return baseRow(booking, passenger, reason, computation)
                .refundAmount(line.getTotalRefunded())
                .fareRefunded(line.getFareRefunded())
                .gstRefunded(line.getGstRefunded())
                .platformFeeRefunded(ZERO)
                .cancellationCharge(line.getCancellationCharge())
                .build();
    }

    /**
     * Everything a refund row shares, whichever scope it was written for.
     */
    private Cancellation.CancellationBuilder baseRow(Booking booking, BookingPassenger passenger, String reason,
                                                     RefundService.RefundComputation computation) {
        return Cancellation.builder()
                .booking(booking)
                .bookingPassenger(passenger)
                .reason(reason)
                .refundStatus(RefundStatus.PROCESSED)
                .scope(computation.scope())
                .refundPercent(BigDecimal.valueOf(computation.tier().getRefundPercent()))
                .daysBeforeJourney(computation.daysBeforeJourney())
                .refundReference(refundService.nextRefundReference())
                // This demo settles refunds immediately. Keeping the field real rather than leaving
                // it null is what makes the PENDING / FAILED states in the enum meaningful later.
                .processedAt(LocalDateTime.now());
    }

    /**
     * A refund reference unique across the ledger, so a passenger quoting it in a support
     * conversation always identifies exactly one refund.
     */
    private RefundRecordDto toRecord(Cancellation cancellation) {
        return RefundRecordDto.builder()
                .id(cancellation.getId())
                .bookingId(String.valueOf(cancellation.getBooking().getId()))
                .bookingReference(cancellation.getBooking().getBookingReference())
                .refundReference(cancellation.getRefundReference())
                .bookingPassengerId(cancellation.getBookingPassenger() == null
                        ? null : cancellation.getBookingPassenger().getId())
                .passengerName(cancellation.getBookingPassenger() == null
                        ? "All passengers"
                        : cancellation.getBookingPassenger().getFullName())
                .reason(cancellation.getReason())
                .refundAmount(cancellation.getRefundAmount())
                .refundStatus(cancellation.getRefundStatus().name())
                .scope(cancellation.getScope().name())
                .fareRefunded(cancellation.getFareRefunded())
                .gstRefunded(cancellation.getGstRefunded())
                .platformFeeRefunded(cancellation.getPlatformFeeRefunded())
                .cancellationCharge(cancellation.getCancellationCharge())
                .refundPercent(cancellation.getRefundPercent().intValue())
                .daysBeforeJourney(cancellation.getDaysBeforeJourney())
                .cancelledAt(cancellation.getCancelledAt())
                .processedAt(cancellation.getProcessedAt())
                .build();
    }
}

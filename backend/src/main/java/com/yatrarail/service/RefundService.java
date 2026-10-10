package com.yatrarail.service;

import com.yatrarail.config.RefundProperties;
import com.yatrarail.dto.payment.PassengerRefundLineDto;
import com.yatrarail.dto.payment.RefundPreviewDto;
import com.yatrarail.dto.ticket.RefundPolicyRowDto;
import com.yatrarail.entity.Booking;
import com.yatrarail.entity.BookingPassenger;
import com.yatrarail.entity.BookingStatus;
import com.yatrarail.entity.CancellationScope;
import com.yatrarail.entity.PassengerStatus;
import com.yatrarail.entity.QuotaType;
import com.yatrarail.exception.BadRequestException;
import com.yatrarail.repository.CancellationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Phase 5 — the cancellation refund policy.
 * <p>
 * This is the single place refund money is decided. Both the preview a passenger sees before
 * committing and the cancellation they then perform are computed here, from the same configured
 * {@link RefundProperties} tiers, so the two can never disagree — which is the whole point of moving
 * the policy out of an {@code if} chain in the booking service and into configuration.
 * <p>
 * <b>What the percentage applies to.</b> The tiered percentage is applied to the fare plus the GST
 * charged on it, and <i>not</i> to the flat platform fee. The fee is a per-booking convenience
 * charge rather than fare, so a <i>full</i> cancellation returns it in full regardless of tier,
 * and a <i>partial</i> cancellation never returns it: the booking still exists, so the charge it
 * carries still stands. This is the Phase 4 behaviour, preserved deliberately.
 * <p>
 * <b>Rounding.</b> Every total is rounded to paise exactly once, and the last line of a split
 * absorbs the difference so that the per-passenger lines always sum to the reported total. A refund
 * whose lines do not add up is worse than no itemisation at all.
 */
@Service
@RequiredArgsConstructor
public class RefundService {

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal ZERO = new BigDecimal("0.00");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final RefundProperties refundProperties;
    private final FareCalculationService fareCalculationService;
    private final CancellationRepository cancellationRepository;

    /**
     * A refund reference unique across the ledger, so a passenger quoting one in a support
     * conversation always identifies exactly one refund.
     * <p>
     * Generated here rather than in each caller so that the whole-booking path and the
     * passenger-level path cannot drift into different formats.
     */
    public String nextRefundReference() {
        String reference;
        do {
            byte[] bytes = new byte[6];
            RANDOM.nextBytes(bytes);
            StringBuilder sb = new StringBuilder("RFN");
            for (byte b : bytes) {
                sb.append(String.format("%02X", b));
            }
            reference = sb.toString();
        } while (cancellationRepository.existsByRefundReference(reference));
        return reference;
    }

    // ------------------------------------------------------------------ policy

    /** The configured tiers, as printed on a ticket and shown next to a refund preview. */
    public List<RefundPolicyRowDto> policyRows() {
        List<RefundPolicyRowDto> rows = new ArrayList<>();
        for (RefundProperties.Tier tier : refundProperties.sorted()) {
            rows.add(RefundPolicyRowDto.builder()
                    .label(tier.getLabel())
                    .fromDaysBeforeJourney(tier.getMinDaysBeforeJourney())
                    .refundPercent(tier.getRefundPercent())
                    .appliesTo("of the fare plus GST for the passenger(s) cancelled")
                    .platformFeeRefunded(refundProperties.isReturnPlatformFeeOnFullCancellation())
                    .build());
        }
        return rows;
    }

    // ------------------------------------------------------------------ eligibility

    /** Whole days between today and departure. Negative once the journey has gone. */
    public long daysBeforeJourney(Booking booking) {
        return ChronoUnit.DAYS.between(LocalDate.now(), booking.getJourneyDate());
    }

    /**
     * Whether this booking can be cancelled at all right now, and if not, why.
     * <p>
     * The paid-state check comes first: an unpaid booking has no money to return, so offering a
     * cancellation would imply a refund that cannot exist. Phase 2 threw a 400 for exactly this case
     * and the message is reused so the behaviour a passenger already knows is unchanged.
     */
    public CancellationCheck checkCancellable(Booking booking) {
        if (booking.getStatus() == BookingStatus.CANCELLED) {
            return CancellationCheck.blocked("This booking has already been cancelled");
        }
        if (booking.getStatus() == BookingStatus.EXPIRED) {
            return CancellationCheck.blocked("This booking already expired; there is nothing to cancel or refund");
        }
        if (booking.getStatus() == BookingStatus.PAYMENT_PENDING
                || booking.getStatus() == BookingStatus.PAYMENT_FAILED) {
            return CancellationCheck.blocked("This booking was never paid for, so there is nothing to refund");
        }
        long days = daysBeforeJourney(booking);
        if (days < 0) {
            return CancellationCheck.blocked("This journey has already occurred and cannot be cancelled");
        }
        if (refundProperties.resolve(days) == null) {
            return CancellationCheck.blocked(
                    "No refund tier is configured for a cancellation " + days + " day(s) before departure");
        }
        return CancellationCheck.allowed(days);
    }

    // ------------------------------------------------------------------ computation

    /**
     * The refund for cancelling {@code selected} passengers of {@code booking}.
     * <p>
     * The returned figures are the ones a cancellation writes to the ledger, so this is the method
     * {@code CancellationService} calls rather than a parallel calculation that could drift.
     *
     * @throws BadRequestException if the selection is empty, or the booking cannot be cancelled
     */
    public RefundComputation compute(Booking booking, List<BookingPassenger> selected) {
        CancellationCheck check = checkCancellable(booking);
        if (!check.allowed()) {
            throw new BadRequestException(check.message());
        }
        if (selected == null || selected.isEmpty()) {
            throw new BadRequestException("Select at least one passenger to cancel");
        }
        RefundProperties.Tier tier = refundProperties.resolve(check.daysBeforeJourney());
        return compute(booking, selected, tier, check.daysBeforeJourney());
    }

    private RefundComputation compute(Booking booking, List<BookingPassenger> selected,
                                      RefundProperties.Tier tier, long days) {
        // Preserve the booking's own passenger order so the refund lines read in the same order as
        // the ticket, rather than in whatever order the caller happened to collect the ids.
        List<BookingPassenger> ordered = booking.getPassengers().stream()
                .filter(p -> selected.stream().anyMatch(s -> s.getId().equals(p.getId())))
                .sorted(Comparator.comparing(BookingPassenger::getId))
                .toList();
        if (ordered.isEmpty()) {
            throw new BadRequestException("Select at least one passenger to cancel");
        }

        // Two questions that are only the same question on a first-time whole-booking cancel, and
        // conflating them is how a staged cancellation came to keep a fee it was owed back.
        boolean coversAllRows = ordered.size() == booking.getPassengers().size();

        // "Does this request select every passenger row?" anchors the refund to the booking's own
        // stored subtotal and decides how many ledger rows are written.
        boolean full = coversAllRows;

        // "Will this leave the booking with nobody travelling on it?" decides whether the platform
        // fee comes back. A passenger who cancels two seats and then the last one reaches the same
        // end state as one who cancels all three at once, and is owed the same refund. EXPIRED is
        // deliberately not counted as already done: an expired passenger leaves the booking EXPIRED
        // rather than CANCELLED, that journey was travelled, and the fee stays with YatraRail.
        long alreadyCancelled = booking.getPassengers().stream()
                .filter(p -> p.getPassengerStatus() == PassengerStatus.CANCELLED)
                .count();
        boolean finishesBooking = ordered.size() + alreadyCancelled >= booking.getPassengers().size();

        // The scope describes the booking's fate, not this request's slice of it, so the request that
        // leaves nobody travelling reads as FULL and is written as a single "All passengers" row
        // carrying the fee. The base anchor above deliberately stays keyed to `coversAllRows`.
        CancellationScope scope = finishesBooking ? CancellationScope.FULL : CancellationScope.PARTIAL;

        BigDecimal platformFee = money(booking.getPlatformFee());
        BigDecimal percent = BigDecimal.valueOf(tier.getRefundPercent());

        // The pre-tier base: the fare actually charged for the selected passengers, plus the GST
        // levied on it. A full selection reads the booking's own stored subtotal rather than
        // re-deriving it, so the refund is anchored to the number the passenger was actually charged
        // instead of to whatever the passenger rows happen to sum to.
        BigDecimal baseFare = full
                ? money(booking.getPassengerFareTotal())
                : money(ordered.stream().map(RefundService::fareOf)
                        .reduce(BigDecimal.ZERO, BigDecimal::add));
        BigDecimal baseGst = full
                ? money(booking.getGstAmount())
                : money(ordered.stream().map(p -> gstOf(p, booking))
                        .reduce(BigDecimal.ZERO, BigDecimal::add));
        BigDecimal baseTotal = money(baseFare.add(baseGst));

        // The tiered amount is computed once for the whole selection, not per passenger, so the
        // lines can never sum to a paisa more than the total that was promised.
        BigDecimal tierRefund = baseTotal.signum() <= 0
                ? ZERO
                : money(baseTotal.multiply(percent).divide(HUNDRED, 2, RoundingMode.HALF_UP));
        BigDecimal charge = money(baseTotal.subtract(tierRefund));

        // The fee is refunded on whichever request actually finishes the booking, not only on a request
        // that happened to select everyone at once. The base above stays keyed to `full`, so the
        // staged case refunds only the newly cancelled passengers' own share of the fare and never
        // re-refunds a seat that an earlier request already paid out.
        BigDecimal platformFeeRefunded = finishesBooking && refundProperties.isReturnPlatformFeeOnFullCancellation()
                ? platformFee
                : ZERO;
        BigDecimal totalRefund = money(tierRefund.add(platformFeeRefunded));

        // Split the tiered amount between fare and GST in proportion to how much of the base each
        // one was. The remainder lands on GST, so fare + gst + fee == totalRefund exactly, which is
        // also the database CHECK constraint on the refund row.
        BigDecimal fareRefunded = ZERO;
        BigDecimal gstRefunded = ZERO;
        if (baseTotal.signum() > 0) {
            fareRefunded = money(tierRefund.multiply(baseFare).divide(baseTotal, 2, RoundingMode.HALF_UP));
            gstRefunded = money(tierRefund.subtract(fareRefunded));
        }

        // Per-passenger lines, prorated by fare share with the last line absorbing the rounding
        // remainder so the lines sum to fareRefunded + gstRefunded exactly. Fare and GST are
        // accumulated separately, so the last line is handed whatever is left of *both* - handing it
        // the whole GST instead would leave the lines over-counting by the other passengers' share.
        BigDecimal allocatedFare = ZERO;
        BigDecimal allocatedGst = ZERO;
        List<PassengerRefundLineDto> lines = new ArrayList<>();
        for (int i = 0; i < ordered.size(); i++) {
            BookingPassenger passenger = ordered.get(i);
            boolean last = i == ordered.size() - 1;
            BigDecimal lineFare;
            BigDecimal lineGst;
            if (last) {
                lineFare = money(fareRefunded.subtract(allocatedFare));
                lineGst = money(gstRefunded.subtract(allocatedGst));
            } else {
                BigDecimal share = baseFare.signum() <= 0
                        ? BigDecimal.ZERO
                        : fareOf(passenger).divide(baseFare, 8, RoundingMode.HALF_UP);
                lineFare = money(fareRefunded.multiply(share));
                lineGst = money(gstRefunded.multiply(share));
                allocatedFare = money(allocatedFare.add(lineFare));
                allocatedGst = money(allocatedGst.add(lineGst));
            }
            BigDecimal lineFarePaid = fareOf(passenger);
            BigDecimal lineGstPaid = gstOf(passenger, booking);
            QuotaType quota = passenger.getQuota() == null ? QuotaType.GENERAL : passenger.getQuota();
            lines.add(PassengerRefundLineDto.builder()
                    .bookingPassengerId(passenger.getId())
                    .fullName(passenger.getFullName())
                    .age(passenger.getAge())
                    .quota(quota.name())
                    .quotaLabel(quota.getLabel())
                    .farePaid(lineFarePaid)
                    .gstPaid(lineGstPaid)
                    .cancellationCharge(money(lineFarePaid.add(lineGstPaid).subtract(lineFare).subtract(lineGst)))
                    .fareRefunded(lineFare)
                    .gstRefunded(lineGst)
                    .totalRefunded(money(lineFare.add(lineGst)))
                    .build());
        }

        return new RefundComputation(booking, ordered, scope, tier, days,
                baseFare, baseGst, baseTotal, tierRefund, charge, platformFee, platformFeeRefunded,
                fareRefunded, gstRefunded, totalRefund, lines);
    }

    /**
     * The same numbers, shaped for the UI. Read-only: no seat is released and no row is written, so
     * a passenger can open and close this as often as they like.
     */
    public RefundPreviewDto preview(Booking booking, List<BookingPassenger> selected) {
        CancellationCheck check = checkCancellable(booking);
        long days = check.allowed() ? check.daysBeforeJourney() : daysBeforeJourney(booking);
        RefundProperties.Tier tier = refundProperties.resolve(days);

        if (!check.allowed() || tier == null) {
            return RefundPreviewDto.builder()
                    .bookingId(booking.getId())
                    .bookingReference(booking.getBookingReference())
                    .scope(CancellationScope.FULL.name())
                    .daysBeforeJourney(days)
                    .journeyDate(booking.getJourneyDate())
                    .lines(List.of())
                    .amountCharged(ZERO)
                    .cancellationCharge(ZERO)
                    .platformFeeCharged(money(booking.getPlatformFee()))
                    .platformFeeRefunded(ZERO)
                    .platformFeeForfeited(money(booking.getPlatformFee()))
                    .fareRefunded(ZERO)
                    .gstRefunded(ZERO)
                    .totalRefund(ZERO)
                    .policy(policyRows())
                    .cancellable(false)
                    .message(check.allowed()
                            ? "No refund tier is configured for a cancellation " + days
                            + " day(s) before departure"
                            : check.message())
                    .build();
        }

        RefundComputation computation = compute(booking, selected, tier, days);
        return RefundPreviewDto.builder()
                .bookingId(booking.getId())
                .bookingReference(booking.getBookingReference())
                .tierLabel(tier.getLabel())
                .refundPercent(tier.getRefundPercent())
                .daysBeforeJourney(days)
                .scope(computation.scope().name())
                .lines(computation.lines())
                .amountCharged(computation.baseTotal())
                .cancellationCharge(computation.charge())
                .platformFeeCharged(computation.platformFee())
                .platformFeeRefunded(computation.platformFeeRefunded())
                .platformFeeForfeited(money(computation.platformFee().subtract(computation.platformFeeRefunded())))
                .fareRefunded(computation.fareRefunded())
                .gstRefunded(computation.gstRefunded())
                .totalRefund(computation.totalRefund())
                .policy(policyRows())
                .cancellable(true)
                .message("Cancelling now returns " + computation.totalRefund()
                        + " under the '" + tier.getLabel() + "' tier")
                .journeyDate(booking.getJourneyDate())
                .build();
    }

    /**
     * The refund a whole-booking cancellation would produce. Kept as a distinct entry point because
     * the Phase 2-4 {@code POST /cancel} endpoint cancels everything and must keep behaving
     * identically; the passenger-level path is additive.
     */
    public RefundComputation computeForWholeBooking(Booking booking) {
        return compute(booking, booking.getPassengers());
    }

    // ------------------------------------------------------------------ helpers

    private static BigDecimal fareOf(BookingPassenger passenger) {
        return money(passenger.getFareAmount());
    }

    private static BigDecimal gstOf(BookingPassenger passenger, Booking booking) {
        BigDecimal fare = fareOf(passenger);
        BigDecimal rate = booking.getGstPercent() == null ? BigDecimal.ZERO : booking.getGstPercent();
        return money(fare.multiply(rate).divide(HUNDRED, 2, RoundingMode.HALF_UP));
    }

    private static BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Whether a passenger row is still cancellable. A passenger already cancelled or expired is not
     * offered again, which is what stops a replayed partial cancellation from refunding the same
     * seat twice.
     */
    public static boolean isLivePassenger(BookingPassenger passenger) {
        return passenger.getPassengerStatus() != null
                && passenger.getPassengerStatus() != PassengerStatus.CANCELLED
                && passenger.getPassengerStatus() != PassengerStatus.EXPIRED;
    }

    /** Whether a selection of passengers can be cancelled right now. */
    public record CancellationCheck(boolean allowed, long daysBeforeJourney, String message) {
        static CancellationCheck allowed(long days) {
            return new CancellationCheck(true, days, "This booking can be cancelled");
        }

        static CancellationCheck blocked(String message) {
            return new CancellationCheck(false, -1, message);
        }
    }

    /**
     * The full arithmetic of one refund, ready to be persisted. Carrying this record rather than
     * recomputing at write time is what guarantees the stored row is the row the passenger was
     * shown.
     */
    public record RefundComputation(Booking booking, List<BookingPassenger> passengers,
                                    CancellationScope scope, RefundProperties.Tier tier,
                                    long daysBeforeJourney, BigDecimal baseFare, BigDecimal baseGst,
                                    BigDecimal baseTotal, BigDecimal tierRefund, BigDecimal charge,
                                    BigDecimal platformFee, BigDecimal platformFeeRefunded,
                                    BigDecimal fareRefunded, BigDecimal gstRefunded,
                                    BigDecimal totalRefund, List<PassengerRefundLineDto> lines) {
    }
}

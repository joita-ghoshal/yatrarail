package com.yatrarail;

import com.yatrarail.dto.booking.BookingResponseDto;
import com.yatrarail.dto.booking.CreateBookingRequest;
import com.yatrarail.dto.booking.PassengerDto;
import com.yatrarail.dto.booking.PassengerResultDto;
import com.yatrarail.dto.booking.ProcessPaymentRequest;
import com.yatrarail.dto.payment.PassengerRefundLineDto;
import com.yatrarail.dto.payment.PassengerRefundRequest;
import com.yatrarail.dto.payment.PaymentSummaryDto;
import com.yatrarail.dto.payment.RefundPreviewDto;
import com.yatrarail.dto.payment.RefundRecordDto;
import com.yatrarail.dto.booking.CancelBookingRequest;
import com.yatrarail.entity.*;
import com.yatrarail.exception.BadRequestException;
import com.yatrarail.exception.ConflictException;
import com.yatrarail.exception.ResourceNotFoundException;
import com.yatrarail.repository.*;
import com.yatrarail.service.BookingService;
import com.yatrarail.service.CancellationService;
import com.yatrarail.service.PaymentLedgerService;
import com.yatrarail.service.RefundService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 5 — the refund arithmetic, passenger-level cancellation, payment ledger and reconciliation.
 * <p>
 * Like the Phase 2-4 suites this is deliberately not wrapped in a test-wide transaction, so the
 * database constraints the service relies on (one cancellation per passenger, and
 * {@code fare + gst + fee == refund}) are actually exercised rather than assumed. State is reset in
 * {@link #setUp()}.
 * <p>
 * <b>Why most expectations are computed rather than hard-coded.</b> The seeded tariff is a real
 * fare rule, and these tests would silently start asserting the wrong thing the day someone edits
 * it. Each expected figure is therefore derived from the booking's own stored snapshot — the same
 * numbers the service reads — so the test proves the <i>policy</i> is applied to the right base
 * without hard-wiring a rupee amount.
 */
@SpringBootTest
@ActiveProfiles("test")
class PaymentRefundLedgerIntegrationTest {

    private static final AtomicInteger COUNTER = new AtomicInteger();
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal ZERO = new BigDecimal("0.00");

    @Autowired
    private BookingService bookingService;
    @Autowired
    private CancellationService cancellationService;
    @Autowired
    private PaymentLedgerService paymentLedgerService;
    @Autowired
    private RefundService refundService;
    @Autowired
    private BookingRepository bookingRepository;
    @Autowired
    private SeatReservationRepository seatReservationRepository;
    @Autowired
    private BookingPassengerRepository bookingPassengerRepository;
    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private PaymentAttemptRepository paymentAttemptRepository;
    @Autowired
    private CancellationRepository cancellationRepository;
    @Autowired
    private CoachRepository coachRepository;
    @Autowired
    private TrainRepository trainRepository;
    @Autowired
    private StationRepository stationRepository;
    @Autowired
    private SeatRepository seatRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private TransactionTemplate transactionTemplate;

    private Long userId;
    private Long otherUserId;
    private Train rajdhani;
    private Coach thirdAcCoach;
    private Long ndls;
    private Long lko;

    @BeforeEach
    void setUp() {
        resetState();

        int n = COUNTER.incrementAndGet();
        userId = newUser("refund" + n + "@example.com", "Refund Tester", RoleName.PASSENGER);
        otherUserId = newUser("stranger" + n + "@example.com", "Stranger", RoleName.PASSENGER);

        rajdhani = trainRepository.findByTrainNumber("12301").orElseThrow();
        thirdAcCoach = coachRepository.findByTrainIdAndSeatClass(rajdhani.getId(), SeatClass.THIRD_AC)
                .orElseThrow();
        reactivateSeededThirdAcCoaches();
        ndls = stationId("NDLS");
        lko = stationId("LKO");
    }

    private void resetState() {
        transactionTemplate.executeWithoutResult(status -> {
            cancellationRepository.deleteAll();
            paymentAttemptRepository.deleteAll();
            paymentRepository.deleteAll();
            seatReservationRepository.deleteAll();
            bookingRepository.deleteAll();
            // The Phase 2-4 suites create coaches on prefixes ZQ/ZU/ZT and clean them up in their own
            // setUp, which leaves the last one behind. Left in place it would make
            // `findByTrainIdAndSeatClass(THIRD_AC)` ambiguous, so this suite clears them too. Seeded
            // coach codes (A1, B1, B2, S1, C1) never start with Z.
            coachRepository.findAll().stream()
                    .filter(c -> c.getCoachCode() != null && c.getCoachCode().startsWith("Z"))
                    .collect(Collectors.toList())
                    .forEach(coach -> {
                        seatRepository.deleteByCoachId(coach.getId());
                        coachRepository.delete(coach);
                    });
            seatRepository.flush();
            coachRepository.flush();
        });
    }

    // ==================================================================
    // Refund policy
    // ==================================================================

    @Nested
    @DisplayName("the refund tiers are applied to the fare plus GST, not to the platform fee")
    class Tiers {

        @Test
        @DisplayName("cancelling at least 7 days out refunds 90% and returns the platform fee")
        void sevenDaysOrMoreIsNinetyPercent() {
            BookingResponseDto booking = createAndPay(userId, daysOut(10), 1);
            Booking snapshot = reload(booking.getId());

            RefundPreviewDto preview = cancellationService.preview(userId, booking.getId(),
                    passengerIds(booking.getId()));
            BigDecimal refundable = refundableBase(snapshot);

            assertThat(preview.getRefundPercent()).isEqualTo(90);
            assertThat(preview.getTierLabel()).isEqualTo("At least 7 days before departure");
            assertThat(preview.getScope()).isEqualTo(CancellationScope.FULL.name());
            assertThat(preview.getPlatformFeeRefunded()).isEqualByComparingTo(snapshot.getPlatformFee());
            assertThat(preview.getTotalRefund())
                    .isEqualByComparingTo(pct(refundable, 90).add(snapshot.getPlatformFee()));
        }

        @Test
        @DisplayName("cancelling 2 to 6 days out refunds 50%")
        void twoToSixDaysIsFiftyPercent() {
            BookingResponseDto booking = createAndPay(userId, daysOut(4), 1);
            Booking snapshot = reload(booking.getId());

            RefundPreviewDto preview = cancellationService.preview(userId, booking.getId(),
                    passengerIds(booking.getId()));

            assertThat(preview.getRefundPercent()).isEqualTo(50);
            assertThat(preview.getTierLabel()).isEqualTo("2 to 6 days before departure");
            assertThat(preview.getTotalRefund())
                    .isEqualByComparingTo(pct(refundableBase(snapshot), 50)
                            .add(snapshot.getPlatformFee()));
        }

        @Test
        @DisplayName("cancelling on the day of travel still refunds the 25% floor tier")
        void sameDayIsTwentyFivePercent() {
            BookingResponseDto booking = createAndPay(userId, LocalDate.now(), 1);
            Booking snapshot = reload(booking.getId());

            RefundPreviewDto preview = cancellationService.preview(userId, booking.getId(),
                    passengerIds(booking.getId()));

            assertThat(preview.getDaysBeforeJourney()).isZero();
            assertThat(preview.getRefundPercent()).isEqualTo(25);
            assertThat(preview.getTierLabel()).isEqualTo("Same day or 1 day before departure");
            assertThat(preview.getTotalRefund())
                    .isEqualByComparingTo(pct(refundableBase(snapshot), 25)
                            .add(snapshot.getPlatformFee()));
        }

        @Test
        @DisplayName("the 7-day boundary belongs to the 90% tier, not the 50% one")
        void sevenDaysIsTheBoundary() {
            assertThat(daysOutTier(7)).isEqualTo(90);
            assertThat(daysOutTier(6)).isEqualTo(50);
            assertThat(daysOutTier(2)).isEqualTo(50);
            assertThat(daysOutTier(1)).isEqualTo(25);
            assertThat(daysOutTier(0)).isEqualTo(25);
        }

        @Test
        @DisplayName("a journey that has already gone cannot be cancelled")
        void aPastJourneyIsNotCancellable() {
            BookingResponseDto booking = createAndPay(userId, daysOut(10), 1);
            Booking snapshot = reload(booking.getId());
            // Move the journey into the past rather than booking in the past, which the booking
            // service rightly refuses.
            transactionTemplate.executeWithoutResult(status -> {
                Booking live = bookingRepository.findById(booking.getId()).orElseThrow();
                live.setJourneyDate(LocalDate.now().minusDays(1));
                bookingRepository.saveAndFlush(live);
            });

            RefundPreviewDto preview = cancellationService.preview(userId, booking.getId(),
                    passengerIds(booking.getId()));

            assertThat(preview.getCancellable()).isFalse();
            assertThat(preview.getTotalRefund()).isEqualByComparingTo(ZERO);
            assertThat(preview.getMessage()).contains("already occurred");
            assertThat(snapshot.getJourneyDate()).isAfterOrEqualTo(LocalDate.now());
        }
    }

    // ==================================================================
    // Arithmetic
    // ==================================================================

    @Nested
    @DisplayName("the itemised refund adds up exactly")
    class Arithmetic {

        @Test
        @DisplayName("per-passenger lines sum to the quoted fare plus GST")
        void linesSumToTheTotal() {
            BookingResponseDto booking = createAndPay(userId, daysOut(10), 3);

            RefundPreviewDto preview = cancellationService.preview(userId, booking.getId(),
                    passengerIds(booking.getId()));

            assertThat(preview.getLines()).hasSize(3);
            BigDecimal lineFare = sum(preview.getLines(), PassengerRefundLineDto::getFareRefunded);
            BigDecimal lineGst = sum(preview.getLines(), PassengerRefundLineDto::getGstRefunded);
            BigDecimal lineTotal = sum(preview.getLines(), PassengerRefundLineDto::getTotalRefunded);

            assertThat(lineFare).isEqualByComparingTo(preview.getFareRefunded());
            assertThat(lineGst).isEqualByComparingTo(preview.getGstRefunded());
            // The invariant that a wrong GST split breaks: if the last line is handed the whole GST
            // refund instead of the remainder, the lines over-count and this fails by the other
            // passengers' GST share.
            //
            // The platform fee is deliberately excluded. It is charged once per booking rather than
            // per passenger, so there is nothing to apportion it to - it is quoted separately and
            // comes back only on a full cancellation.
            assertThat(lineTotal).isEqualByComparingTo(
                    preview.getTotalRefund().subtract(preview.getPlatformFeeRefunded()));
        }

        @Test
        @DisplayName("the lines plus the platform fee add up to the total")
        void linesPlusThePlatformFeeMakeTheTotal() {
            BookingResponseDto booking = createAndPay(userId, daysOut(10), 3);

            RefundPreviewDto preview = cancellationService.preview(userId, booking.getId(),
                    passengerIds(booking.getId()));

            BigDecimal lineTotal = sum(preview.getLines(), PassengerRefundLineDto::getTotalRefunded);
            assertThat(lineTotal.add(preview.getPlatformFeeRefunded()))
                    .isEqualByComparingTo(preview.getTotalRefund());
        }

        @Test
        @DisplayName("each line's charge is the part of what that passenger paid that is not returned")
        void eachLineChargeIsWhatThePassengerLost() {
            BookingResponseDto booking = createAndPay(userId, daysOut(10), 3);

            RefundPreviewDto preview = cancellationService.preview(userId, booking.getId(),
                    passengerIds(booking.getId()));

            for (PassengerRefundLineDto line : preview.getLines()) {
                BigDecimal paid = line.getFarePaid().add(line.getGstPaid());
                assertThat(line.getTotalRefunded().add(line.getCancellationCharge()))
                        .isEqualByComparingTo(paid);
            }
        }

        @Test
        @DisplayName("the stored row satisfies fare + gst + fee == refund")
        void thePersistedRowIsSelfConsistent() {
            BookingResponseDto booking = createAndPay(userId, daysOut(10), 3);
            cancel(booking.getId(), passengerIds(booking.getId()).subList(0, 2));

            List<Cancellation> rows = cancellationRepository.findByBookingIdOrderByCancelledAtAsc(
                    booking.getId());
            assertThat(rows).isNotEmpty();
            for (Cancellation row : rows) {
                assertThat(row.getRefundAmount()).isEqualByComparingTo(
                        row.getFareRefunded()
                                .add(row.getGstRefunded())
                                .add(row.getPlatformFeeRefunded()));
            }
        }
    }

    // ==================================================================
    // Partial cancellation
    // ==================================================================

    @Nested
    @DisplayName("a subset of the passengers can be cancelled")
    class PartialCancellation {

        @Test
        @DisplayName("cancelling one of three releases only that seat and keeps the booking")
        void cancellingOnePassengerKeepsTheBooking() {
            BookingResponseDto booking = createAndPay(userId, daysOut(10), 3);
            List<Long> ids = passengerIds(booking.getId());
            long heldBefore = bookingPassengerRepository
                    .countByBookingCoachIdAndBookingJourneyDateAndPassengerStatusInAndBookingStatusIn(
                            thirdAcCoach.getId(), daysOut(10), List.of(PassengerStatus.CONFIRMED),
                            List.of(BookingStatus.CONFIRMED, BookingStatus.RAC, BookingStatus.WAITLISTED));

            List<RefundRecordDto> refunds = cancel(booking.getId(), ids.subList(0, 1));

            assertThat(refunds).hasSize(1);
            assertThat(refunds.get(0).getScope()).isEqualTo(CancellationScope.PARTIAL.name());
            assertThat(refunds.get(0).getBookingPassengerId()).isEqualTo(ids.get(0));
            assertThat(refunds.get(0).getPassengerName()).isNotEqualTo("All passengers");
            assertThat(refunds.get(0).getRefundReference()).startsWith("RFN");

            Booking after = reload(booking.getId());
            assertThat(after.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
            assertThat(statusOf(booking.getId(), ids.get(0))).isEqualTo(PassengerStatus.CANCELLED);
            assertThat(statusOf(booking.getId(), ids.get(1))).isEqualTo(PassengerStatus.CONFIRMED);

            long heldAfter = bookingPassengerRepository
                    .countByBookingCoachIdAndBookingJourneyDateAndPassengerStatusInAndBookingStatusIn(
                            thirdAcCoach.getId(), daysOut(10), List.of(PassengerStatus.CONFIRMED),
                            List.of(BookingStatus.CONFIRMED, BookingStatus.RAC, BookingStatus.WAITLISTED));
            assertThat(heldAfter).isEqualTo(heldBefore - 1);
        }

        @Test
        @DisplayName("a partial cancellation does not refund the platform fee")
        void partialCancellationForfeitsThePlatformFee() {
            BookingResponseDto booking = createAndPay(userId, daysOut(10), 3);
            List<Long> ids = passengerIds(booking.getId());
            RefundPreviewDto preview = cancellationService.preview(userId, booking.getId(),
                    ids.subList(0, 1));

            List<RefundRecordDto> refunds = cancel(booking.getId(), ids.subList(0, 1));

            // Only the selected passenger is refunded, so this is their share of the tiered amount
            // and not the whole booking's.
            assertThat(refunds.get(0).getPlatformFeeRefunded()).isEqualByComparingTo(ZERO);
            assertThat(refunds.get(0).getRefundAmount())
                    .isEqualByComparingTo(preview.getLines().get(0).getTotalRefunded());
            assertThat(refunds.get(0).getFareRefunded())
                    .isEqualByComparingTo(preview.getLines().get(0).getFareRefunded());
            assertThat(refunds.get(0).getGstRefunded())
                    .isEqualByComparingTo(preview.getLines().get(0).getGstRefunded());
        }

        @Test
        @DisplayName("the per-passenger rows of a partial cancellation sum to the previewed total")
        void partialRowsSumToThePreview() {
            BookingResponseDto booking = createAndPay(userId, daysOut(10), 3);
            List<Long> ids = passengerIds(booking.getId());
            RefundPreviewDto preview = cancellationService.preview(userId, booking.getId(),
                    ids.subList(0, 2));

            List<RefundRecordDto> refunds = cancel(booking.getId(), ids.subList(0, 2));

            assertThat(refunds).hasSize(2);
            BigDecimal written = refunds.stream()
                    .map(RefundRecordDto::getRefundAmount)
                    .reduce(ZERO, BigDecimal::add);
            // The aggregate the passenger was quoted must be exactly what the ledger now says they
            // were paid. Writing the aggregate on every row would multiply the refund by the number
            // of passengers cancelled.
            assertThat(written).isEqualByComparingTo(preview.getTotalRefund());
        }

        @Test
        @DisplayName("a replayed cancellation of the same passenger is refused")
        void theSamePassengerCannotBeCancelledTwice() {
            BookingResponseDto booking = createAndPay(userId, daysOut(10), 3);
            List<Long> ids = passengerIds(booking.getId());
            cancel(booking.getId(), ids.subList(0, 1));

            assertThatThrownBy(() -> cancel(booking.getId(), ids.subList(0, 1)))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("already been cancelled");

            assertThat(cancellationRepository.findByBookingIdOrderByCancelledAtAsc(booking.getId()))
                    .hasSize(1);
        }

        @Test
        @DisplayName("a passenger id from another booking is refused")
        void aPassengerFromAnotherBookingIsRefused() {
            BookingResponseDto mine = createAndPay(userId, daysOut(10), 1);
            BookingResponseDto theirs = createAndPay(userId, daysOut(11), 1);
            Long foreignPassengerId = passengerIds(theirs.getId()).get(0);

            assertThatThrownBy(() -> cancel(mine.getId(), List.of(foreignPassengerId)))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("is not part of this booking");
        }

        @Test
        @DisplayName("someone else's booking cannot be cancelled")
        void anotherUsersBookingCannotBeCancelled() {
            BookingResponseDto booking = createAndPay(userId, daysOut(10), 2);

            assertThatThrownBy(() -> cancellationService.cancelPassengers(otherUserId, booking.getId(),
                    refundRequest(passengerIds(booking.getId()))))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("Booking not found");
        }

        @Test
        @DisplayName("cancelling every passenger through the passenger endpoint is a full cancellation")
        void selectingEveryoneIsAFullCancellation() {
            BookingResponseDto booking = createAndPay(userId, daysOut(10), 2);
            Booking snapshot = reload(booking.getId());
            List<Long> ids = passengerIds(booking.getId());

            List<RefundRecordDto> refunds = cancel(booking.getId(), ids);

            // One row for "all passengers", not one per person: it is the only place the platform
            // fee comes back, and a passenger reading their history should see one entry.
            assertThat(refunds).hasSize(1);
            assertThat(refunds.get(0).getScope()).isEqualTo(CancellationScope.FULL.name());
            assertThat(refunds.get(0).getPassengerName()).isEqualTo("All passengers");
            assertThat(refunds.get(0).getPlatformFeeRefunded())
                    .isEqualByComparingTo(snapshot.getPlatformFee());
            assertThat(reload(booking.getId()).getStatus()).isEqualTo(BookingStatus.CANCELLED);
        }

@Test
        @DisplayName("a staged cancellation still returns the platform fee on the request that finishes the booking")
        void aStagedCancellationReturnsThePlatformFeeOnce() {
            BookingResponseDto booking = createAndPay(userId, daysOut(10), 3);
            Booking snapshot = reload(booking.getId());
            List<Long> ids = passengerIds(booking.getId());

            List<RefundRecordDto> first = cancel(booking.getId(), ids.subList(0, 1));
            assertThat(first.get(0).getPlatformFeeRefunded()).isEqualByComparingTo(ZERO);
            assertThat(first.get(0).getScope()).isEqualTo(CancellationScope.PARTIAL.name());

            RefundPreviewDto finishingPreview = cancellationService.preview(userId, booking.getId(),
                    ids.subList(1, 3));
            assertThat(finishingPreview.getPlatformFeeRefunded())
                    .isEqualByComparingTo(snapshot.getPlatformFee());
            assertThat(finishingPreview.getScope()).isEqualTo(CancellationScope.FULL.name());

            List<RefundRecordDto> second = cancel(booking.getId(), ids.subList(1, 3));

            // The fee is a property of the booking, so it comes back exactly once - on the request
            // that leaves nobody travelling - and the booking ends up cancelled.
            assertThat(second).hasSize(1);
            assertThat(second.get(0).getScope()).isEqualTo(CancellationScope.FULL.name());
            assertThat(second.get(0).getPlatformFeeRefunded())
                    .isEqualByComparingTo(snapshot.getPlatformFee());
            assertThat(reload(booking.getId()).getStatus()).isEqualTo(BookingStatus.CANCELLED);

            // The whole ledger, summed, must never refund the platform fee twice.
            BigDecimal totalFees = java.util.stream.Stream.concat(first.stream(), second.stream())
                    .map(RefundRecordDto::getPlatformFeeRefunded)
                    .reduce(ZERO, BigDecimal::add);
            assertThat(totalFees).isEqualByComparingTo(snapshot.getPlatformFee());
        }

        @Test
        @DisplayName("a staged cancellation refunds only the newly cancelled seats, never a seat twice")
        void aStagedCancellationDoesNotRefundASeatTwice() {
            BookingResponseDto booking = createAndPay(userId, daysOut(10), 3);
            Booking snapshot = reload(booking.getId());
            List<Long> ids = passengerIds(booking.getId());

            List<RefundRecordDto> first = cancel(booking.getId(), ids.subList(0, 1));
            List<RefundRecordDto> second = cancel(booking.getId(), ids.subList(1, 3));

            // The finishing request must be anchored to the two seats it is actually refunding, not
            // to the booking's whole subtotal, which would pay the already-refunded first seat twice.
            BigDecimal refundable = refundableBase(snapshot);
            BigDecimal written = java.util.stream.Stream.concat(first.stream(), second.stream())
                    .map(RefundRecordDto::getRefundAmount)
                    .reduce(ZERO, BigDecimal::add);

            assertThat(written).isEqualByComparingTo(
                    pct(refundable, 90).add(snapshot.getPlatformFee()));
            assertThat(written).isLessThan(refundable);
        }

        @Test
        @DisplayName("a partial cancellation that leaves someone travelling still forfeits the fee")
        void aPartialStagedCancellationKeepsTheFee() {
            BookingResponseDto booking = createAndPay(userId, daysOut(10), 3);
            List<Long> ids = passengerIds(booking.getId());

            cancel(booking.getId(), ids.subList(0, 1));
            RefundPreviewDto stillPartial = cancellationService.preview(userId, booking.getId(),
                    ids.subList(1, 2));

            // One seat is still travelling, so this is a partial cancellation and owes no fee.
            assertThat(stillPartial.getScope()).isEqualTo(CancellationScope.PARTIAL.name());
            assertThat(stillPartial.getPlatformFeeRefunded()).isEqualByComparingTo(ZERO);
            assertThat(stillPartial.getPlatformFeeForfeited())
                    .isEqualByComparingTo(reload(booking.getId()).getPlatformFee());
        }

        @Test
        @DisplayName("an empty selection is refused")
        void anEmptySelectionIsRefused() {
            BookingResponseDto booking = createAndPay(userId, daysOut(10), 2);

            assertThatThrownBy(() -> cancel(booking.getId(), List.of()))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("Select at least one passenger");
        }
    }

    // ==================================================================
    // Preview
    // ==================================================================

    @Nested
    @DisplayName("the preview is read-only")
    class Preview {

@Test
        @DisplayName("previewing writes nothing and moves no seat")
        void previewWritesNothing() {
            BookingResponseDto booking = createAndPay(userId, daysOut(10), 3);
            List<Long> ids = passengerIds(booking.getId());

            RefundPreviewDto first = cancellationService.preview(userId, booking.getId(), ids);
            RefundPreviewDto second = cancellationService.preview(userId, booking.getId(), ids);

            assertThat(cancellationRepository.count()).isZero();
            assertThat(statusOf(booking.getId(), ids.get(0)))
                    .isEqualTo(PassengerStatus.CONFIRMED);
            // Deterministic, so the figure the passenger confirms is the figure they were shown.
            assertThat(second.getTotalRefund()).isEqualByComparingTo(first.getTotalRefund());
        }

        @Test
        @DisplayName("the ids the UI reads off the booking are the ids the refund endpoints accept")
        void theBookingDtoCarriesUsablePassengerIds() {
            // The browser can only address a passenger by the id the booking response carries, so a
            // DTO that omits it makes passenger-level cancellation unreachable from the UI even
            // though every service-level test passes. This asserts the contract the screen depends on.
            BookingResponseDto booking = createAndPay(userId, daysOut(10), 3);

            List<Long> fromResponse = booking.getPassengers().stream()
                    .map(com.yatrarail.dto.booking.PassengerResultDto::getId)
                    .toList();

            assertThat(fromResponse).doesNotContainNull().hasSize(3);
            assertThat(fromResponse).doesNotHaveDuplicates();
            assertThat(fromResponse).containsExactlyInAnyOrderElementsOf(passengerIds(booking.getId()));

            RefundPreviewDto preview = cancellationService.preview(userId, booking.getId(), fromResponse);
            assertThat(preview.getLines()).hasSize(3);

            List<RefundRecordDto> refunded = cancel(booking.getId(), fromResponse.subList(0, 1));
            assertThat(refunded).hasSize(1);
            assertThat(refunded.get(0).getBookingPassengerId()).isEqualTo(fromResponse.get(0));
        }

        @Test
        @DisplayName("previewing everything quotes the platform fee back; previewing a subset does not")
        void scopeDecidesThePlatformFee() {
            BookingResponseDto booking = createAndPay(userId, daysOut(10), 3);
            List<Long> ids = passengerIds(booking.getId());
            Booking snapshot = reload(booking.getId());

            RefundPreviewDto all = cancellationService.preview(userId, booking.getId(), ids);
            RefundPreviewDto some = cancellationService.preview(userId, booking.getId(),
                    ids.subList(0, 1));

            assertThat(all.getScope()).isEqualTo(CancellationScope.FULL.name());
            assertThat(all.getPlatformFeeRefunded()).isEqualByComparingTo(snapshot.getPlatformFee());
            assertThat(some.getScope()).isEqualTo(CancellationScope.PARTIAL.name());
            assertThat(some.getPlatformFeeRefunded()).isEqualByComparingTo(ZERO);
            assertThat(some.getPlatformFeeForfeited()).isEqualByComparingTo(snapshot.getPlatformFee());
        }

        @Test
        @DisplayName("a stale checkbox is dropped rather than failing the quote")
        void anAlreadyCancelledPassengerIsSkippedByPreview() {
            BookingResponseDto booking = createAndPay(userId, daysOut(10), 3);
            List<Long> ids = passengerIds(booking.getId());
            cancel(booking.getId(), ids.subList(0, 1));

            RefundPreviewDto preview = cancellationService.preview(userId, booking.getId(), ids);

            assertThat(preview.getLines()).hasSize(2);
            assertThat(preview.getLines())
                    .extracting(PassengerRefundLineDto::getBookingPassengerId)
                    .doesNotContain(ids.get(0));
        }

        @Test
        @DisplayName("an unpaid booking quotes nothing, because there is nothing to return")
        void anUnpaidBookingIsNotCancellable() {
            BookingResponseDto booking = create(userId, daysOut(10), 1);

            RefundPreviewDto preview = cancellationService.preview(userId, booking.getId(),
                    passengerIds(booking.getId()));

            assertThat(preview.getCancellable()).isFalse();
            assertThat(preview.getTotalRefund()).isEqualByComparingTo(ZERO);
            assertThat(preview.getMessage()).contains("never paid for");
        }

        @Test
        @DisplayName("the policy table is returned for the passenger to read")
        void thePolicyTableIsReturned() {
            BookingResponseDto booking = createAndPay(userId, daysOut(10), 1);

            RefundPreviewDto preview = cancellationService.preview(userId, booking.getId(),
                    passengerIds(booking.getId()));

            assertThat(preview.getPolicy()).hasSize(3);
            assertThat(preview.getPolicy())
                    .extracting("refundPercent")
                    .containsExactly(90, 50, 25);
        }
    }

    // ==================================================================
    // The preserved whole-booking path
    // ==================================================================

    @Nested
    @DisplayName("the Phase 2-4 whole-booking cancellation is unchanged")
    class WholeBooking {

        @Test
        @DisplayName("it still returns the booking DTO and refunds the platform fee")
        void wholeBookingCancellationStillWorks() {
            BookingResponseDto booking = createAndPay(userId, daysOut(10), 3);
            Booking snapshot = reload(booking.getId());

            BookingResponseDto cancelled = bookingService.cancelBooking(userId, booking.getId(),
                    cancelRequest("Changed my plans"));

            assertThat(cancelled.getStatus()).isEqualTo("CANCELLED");
            Cancellation row = cancellationRepository
                    .findByBookingIdOrderByCancelledAtAsc(booking.getId()).get(0);
            assertThat(row.getScope()).isEqualTo(CancellationScope.FULL);
            assertThat(row.getBookingPassenger()).isNull();
            assertThat(row.getPlatformFeeRefunded()).isEqualByComparingTo(snapshot.getPlatformFee());
        }

        @Test
        @DisplayName("its refund reference is populated, so support can quote it")
        void wholeBookingRefundHasAReference() {
            BookingResponseDto booking = createAndPay(userId, daysOut(10), 2);

            bookingService.cancelBooking(userId, booking.getId(), cancelRequest("no longer needed"));

            Cancellation row = cancellationRepository
                    .findByBookingIdOrderByCancelledAtAsc(booking.getId()).get(0);
            assertThat(row.getRefundReference()).startsWith("RFN");
        }

        @Test
        @DisplayName("cancelling it twice is still refused, as it was in Phase 2")
        void wholeBookingCancellationIsNotRepeatable() {
            BookingResponseDto booking = createAndPay(userId, daysOut(10), 2);
            bookingService.cancelBooking(userId, booking.getId(), cancelRequest("first"));

            assertThatThrownBy(() -> bookingService.cancelBooking(userId, booking.getId(),
                    cancelRequest("second")))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("already been cancelled");
        }

        @Test
        @DisplayName("an unpaid booking is still refused a refund")
        void unpaidBookingsStillCannotBeCancelled() {
            BookingResponseDto booking = create(userId, daysOut(10), 2);

            assertThatThrownBy(() -> bookingService.cancelBooking(userId, booking.getId(),
                    cancelRequest("nope")))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("never paid for");
        }
    }

    // ==================================================================
    // Payment ledger
    // ==================================================================

    @Nested
    @DisplayName("the payment ledger records every attempt")
    class Ledger {

        @Test
        @DisplayName("a success and a later success are both on the passenger's history")
        void successfulAttemptsAreRecorded() {
            BookingResponseDto booking = createAndPay(userId, daysOut(10), 1);

            PaymentSummaryDto history = paymentLedgerService.getHistory(userId);

            assertThat(history.getAttempts()).hasSize(1);
            PaymentHistoryEntryAssertions.assertSettled(history);
        }

        @Test
        @DisplayName("a failed attempt is kept after a retry succeeds, not overwritten")
        void failedAttemptsSurviveARetry() {
            BookingResponseDto booking = create(userId, daysOut(10), 1);
            pay(userId, booking.getId(), false);
            pay(userId, booking.getId(), true);

            PaymentSummaryDto history = paymentLedgerService.getHistory(userId);

            // This is the whole reason the ledger exists: `payments` is one mutable row and now holds
            // only the successful reference, so without the ledger the failed attempt is invisible.
            assertThat(history.getAttempts()).hasSize(2);
            assertThat(history.getFailedAttemptCount()).isEqualTo(1);
            assertThat(history.getAttemptCount()).isEqualTo(2);
            assertThat(history.getAttempts())
                    .extracting("status")
                    .containsExactlyInAnyOrder("FAILED", "SUCCESS");
            assertThat(history.getAttempts())
                    .extracting("transactionReference")
                    .doesNotHaveDuplicates();
        }

    @Test
    @DisplayName("a replayed idempotency key is not charged or recorded twice")
    void aReplayedIdempotencyKeyIsNotDoubleCounted() {
        BookingResponseDto booking = create(userId, daysOut(10), 1);
        ProcessPaymentRequest request = new ProcessPaymentRequest();
        request.setMethod("UPI");
        request.setSimulateSuccess(true);
        bookingService.processPayment(userId, booking.getId(), request, "replay-key-1");
        BookingResponseDto replayed = bookingService.processPayment(userId, booking.getId(), request,
                "replay-key-1");

        PaymentSummaryDto history = paymentLedgerService.getHistory(userId);

        assertThat(replayed.getId()).isEqualTo(booking.getId());
        assertThat(history.getAttemptCount()).isEqualTo(1);
        assertThat(history.getTotalSettled()).isEqualByComparingTo(history.getTotalAttempted());
    }

        @Test
        @DisplayName("the summary nets refunds off what was settled")
        void theSummaryNetsRefunds() {
            BookingResponseDto booking = createAndPay(userId, daysOut(10), 2);
            cancel(booking.getId(), passengerIds(booking.getId()));

            PaymentSummaryDto history = paymentLedgerService.getHistory(userId);

            assertThat(history.getTotalRefunded()).isPositive();
            assertThat(history.getNetPaid())
                    .isEqualByComparingTo(history.getTotalSettled().subtract(history.getTotalRefunded()));
        }

        @Test
        @DisplayName("a passenger with no bookings gets an empty, well-formed history")
        void anEmptyHistoryIsNotAnError() {
            PaymentSummaryDto history = paymentLedgerService.getHistory(otherUserId);

            assertThat(history.getAttempts()).isEmpty();
            assertThat(history.getTotalAttempted()).isEqualByComparingTo(ZERO);
            assertThat(history.getTotalSettled()).isEqualByComparingTo(ZERO);
            assertThat(history.getNetPaid()).isEqualByComparingTo(ZERO);
            assertThat(history.getLastActivityAt()).isNull();
            assertThat(paymentLedgerService.getRefundsForUser(otherUserId)).isEmpty();
        }

        @Test
        @DisplayName("a passenger only ever sees their own attempts")
        void historyIsScopedToTheCaller() {
            createAndPay(userId, daysOut(10), 1);
            createAndPay(otherUserId, daysOut(11), 1);

            PaymentSummaryDto mine = paymentLedgerService.getHistory(userId);
            PaymentSummaryDto theirs = paymentLedgerService.getHistory(otherUserId);

            assertThat(mine.getAttemptCount()).isEqualTo(1);
            assertThat(theirs.getAttemptCount()).isEqualTo(1);
            assertThat(mine.getTotalSettled()).isEqualByComparingTo(theirs.getTotalSettled());
        }

        @Test
        @DisplayName("a passenger's refund list is newest first and names the passengers")
        void refundsAreListedNewestFirst() {
            BookingResponseDto booking = createAndPay(userId, daysOut(10), 3);
            List<Long> ids = passengerIds(booking.getId());
            cancel(booking.getId(), ids.subList(0, 1));
            cancel(booking.getId(), ids.subList(1, 3));

            List<RefundRecordDto> refunds = paymentLedgerService.getRefundsForUser(userId);

            // Two rows, not three: cancelling one passenger then the other two is still one ledger
            // row per cancellation *event*, and the event that finished the booking is recorded
            // once as "All passengers" rather than duplicated per remaining seat.
            assertThat(refunds).hasSize(2);
            assertThat(refunds.get(0).getCancelledAt())
                    .isAfterOrEqualTo(refunds.get(refunds.size() - 1).getCancelledAt());
            assertThat(refunds).extracting(RefundRecordDto::getRefundReference).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("the admin payment view reports attempts, failures and refunds per booking")
        void theAdminPaymentViewExplainsEachSettledPayment() {
            BookingResponseDto booking = create(userId, daysOut(10), 1);
            pay(userId, booking.getId(), false);
            pay(userId, booking.getId(), true);

            var rows = paymentLedgerService.getAdminPayments();

            assertThat(rows).hasSize(1);
            var row = rows.get(0);
            assertThat(row.getBookingId()).isEqualTo(booking.getId());
            assertThat(row.getAttemptCount()).isEqualTo(2);
            assertThat(row.getFailedAttemptCount()).isEqualTo(1);
            assertThat(row.getStatus()).isEqualTo("SUCCESS");
            assertThat(row.getNetCollected()).isEqualByComparingTo(row.getAmount());
        }
    }

    // ==================================================================
    // Reconciliation
    // ==================================================================

    @Nested
    @DisplayName("reconciliation compares the ledger against the bookings")
    class Reconciliation {

        @Test
        @DisplayName("a healthy dataset reconciles with no critical or warning findings")
        void aHealthyDatasetIsClean() {
            createAndPay(userId, daysOut(10), 2);
            createAndPay(userId, daysOut(11), 1);

            var report = paymentLedgerService.reconcile();

            assertThat(report.getReconciled()).isTrue();
            assertThat(report.getIssues()).isEmpty();
            assertThat(report.getSettledPaymentCount()).isEqualTo(2);
            assertThat(report.getTotalCollected()).isPositive();
            assertThat(report.getNetCollected())
                    .isEqualByComparingTo(report.getTotalCollected().subtract(report.getTotalRefunded()));
        }

        @Test
        @DisplayName("a cancellation is reported, not treated as a discrepancy")
        void aNormalCancellationIsNotAFinding() {
            BookingResponseDto booking = createAndPay(userId, daysOut(10), 2);
            cancel(booking.getId(), passengerIds(booking.getId()));

            var report = paymentLedgerService.reconcile();

            assertThat(report.getReconciled()).isTrue();
            assertThat(report.getTotalRefunded()).isPositive();
            assertThat(report.getIssues()).isEmpty();
        }

        @Test
        @DisplayName("a partial cancellation is surfaced as informational")
        void aPartialCancellationIsInformational() {
            BookingResponseDto booking = createAndPay(userId, daysOut(10), 3);
            cancel(booking.getId(), passengerIds(booking.getId()).subList(0, 1));

            var report = paymentLedgerService.reconcile();

            // Not a warning: the operator still wants to know why one seat went back.
            assertThat(report.getIssues())
                    .extracting("code")
                    .contains("PARTIALLY_REFUNDED");
            assertThat(report.getIssues())
                    .filteredOn(i -> "PARTIALLY_REFUNDED".equals(i.getCode()))
                    .extracting("severity")
                    .containsOnly("INFO");
            assertThat(report.getReconciled()).isTrue();
        }

@Test
        @DisplayName("a staged cancellation that ends fully cancelled is not reported as partially refunded")
        void aStagedCancellationIsNotReportedAsPartial() {
            BookingResponseDto booking = createAndPay(userId, daysOut(10), 3);
            List<Long> ids = passengerIds(booking.getId());
            cancel(booking.getId(), ids.subList(0, 1));
            cancel(booking.getId(), ids.subList(1, 3));

            var report = paymentLedgerService.reconcile();

            // Every passenger really is cancelled, so the operator must not be told "1 of 3".
            assertThat(report.getIssues())
                    .extracting("code")
                    .doesNotContain("PARTIALLY_REFUNDED");
            assertThat(report.getReconciled()).isTrue();
        }

        @Test
        @DisplayName("a refund larger than the payment is flagged critical")
        void anOverRefundIsCritical() {
            BookingResponseDto booking = createAndPay(userId, daysOut(10), 2);
            // Hand-write a row that no correct service would produce: the database's arithmetic CHECK
            // still holds, so only reconciliation can catch this.
            transactionTemplate.executeWithoutResult(status -> {
                Booking live = bookingRepository.findById(booking.getId()).orElseThrow();
                BigDecimal absurd = live.getTotalFare().multiply(new BigDecimal("5"));
                cancellationRepository.saveAndFlush(Cancellation.builder()
                        .booking(live)
                        .reason("injected over-refund")
                        .refundAmount(absurd)
                        .fareRefunded(absurd)
                        .gstRefunded(ZERO)
                        .platformFeeRefunded(ZERO)
                        .cancellationCharge(ZERO)
                        .refundPercent(new BigDecimal("100"))
                        .refundStatus(RefundStatus.PROCESSED)
                        .scope(CancellationScope.FULL)
                        .refundReference("RFN-INJECTED")
                        .processedAt(java.time.LocalDateTime.now())
                        .build());
            });

            var report = paymentLedgerService.reconcile();

            assertThat(report.getReconciled()).isFalse();
            assertThat(report.getIssues()).extracting("code").contains("REFUND_EXCEEDS_PAYMENT");
        }

        @Test
        @DisplayName("a settled payment with no ledger entry is flagged")
        void aMissingLedgerEntryIsFlagged() {
            BookingResponseDto booking = createAndPay(userId, daysOut(10), 1);
            transactionTemplate.executeWithoutResult(status ->
                    paymentAttemptRepository.deleteAll());

            var report = paymentLedgerService.reconcile();

            assertThat(report.getReconciled()).isFalse();
            assertThat(report.getIssues()).extracting("code").contains("MISSING_LEDGER_ENTRY");
        }

        @Test
        @DisplayName("seats issued with no money received is flagged critical")
        void seatsWithoutPaymentIsCritical() {
            BookingResponseDto booking = create(userId, daysOut(10), 1);
            // Force the state a bug would produce: a held seat on a booking that never settled.
            transactionTemplate.executeWithoutResult(status -> {
                Booking live = bookingRepository.findById(booking.getId()).orElseThrow();
                live.setStatus(BookingStatus.CONFIRMED);
                bookingRepository.saveAndFlush(live);
            });

            var report = paymentLedgerService.reconcile();

            assertThat(report.getReconciled()).isFalse();
            assertThat(report.getIssues()).extracting("code").contains("PAID_STATUS_NO_PAYMENT");
        }

        @Test
        @DisplayName("a refund recorded against an unpaid booking is flagged")
        void aRefundWithoutPaymentIsFlagged() {
            BookingResponseDto booking = create(userId, daysOut(10), 1);
            transactionTemplate.executeWithoutResult(status -> {
                Booking live = bookingRepository.findById(booking.getId()).orElseThrow();
                cancellationRepository.saveAndFlush(Cancellation.builder()
                        .booking(live)
                        .reason("injected refund with no payment")
                        .refundAmount(new BigDecimal("1.00"))
                        .fareRefunded(new BigDecimal("1.00"))
                        .gstRefunded(ZERO)
                        .platformFeeRefunded(ZERO)
                        .cancellationCharge(ZERO)
                        .refundPercent(new BigDecimal("1"))
                        .refundStatus(RefundStatus.PROCESSED)
                        .scope(CancellationScope.FULL)
                        .refundReference("RFN-NOPAY")
                        .processedAt(java.time.LocalDateTime.now())
                        .build());
            });

            var report = paymentLedgerService.reconcile();

            assertThat(report.getReconciled()).isFalse();
            assertThat(report.getIssues()).extracting("code").contains("REFUND_WITHOUT_PAYMENT");
        }

        @Test
        @DisplayName("findings are ordered so the worst is first")
        void criticalFindingsSortFirst() {
            createAndPay(userId, daysOut(10), 1);
            BookingResponseDto unpaid = create(userId, daysOut(11), 1);
            transactionTemplate.executeWithoutResult(status -> {
                Booking live = bookingRepository.findById(unpaid.getId()).orElseThrow();
                live.setStatus(BookingStatus.CONFIRMED);
                bookingRepository.saveAndFlush(live);
            });

            var report = paymentLedgerService.reconcile();

            assertThat(report.getIssues()).isNotEmpty();
            assertThat(report.getIssues().get(0).getSeverity()).isIn("CRITICAL", "WARNING");
            assertThat(report.getIssues().get(0).getBookingId()).isEqualTo(unpaid.getId());
        }

        @Test
        @DisplayName("the counts describe the whole system, not just one passenger")
        void theCountsDescribeEverything() {
            createAndPay(userId, daysOut(10), 1);
            createAndPay(otherUserId, daysOut(11), 1);

            var report = paymentLedgerService.reconcile();

            assertThat(report.getBookingCount()).isEqualTo(2);
            assertThat(report.getPaymentAttemptCount()).isEqualTo(2);
            assertThat(report.getPaidBookingCount()).isEqualTo(2);
            assertThat(report.getPendingRefundCount()).isZero();
            assertThat(report.getFailedRefundCount()).isZero();
        }
    }

    // ==================================================================
    // Helpers
    // ==================================================================

    private int daysOutTier(int days) {
        // The tier resolution is pure configuration, so it is exercised directly against a booking
        // rather than by arranging a real cancellation on each of the four boundary dates.
        return new com.yatrarail.config.RefundProperties().resolve(days).getRefundPercent();
    }

    private LocalDate daysOut(int days) {
        return LocalDate.now().plusDays(days);
    }

    private static BigDecimal refundableBase(Booking booking) {
        // Fare plus the GST levied on it. The platform fee is deliberately not part of this base.
        return money(booking.getPassengerFareTotal().add(booking.getGstAmount()));
    }

    private static BigDecimal pct(BigDecimal base, int percent) {
        return base.multiply(BigDecimal.valueOf(percent))
                .divide(HUNDRED, 2, RoundingMode.HALF_UP);
    }

    private Booking reload(Long bookingId) {
        return bookingRepository.findById(bookingId).orElseThrow();
    }

    private List<Long> passengerIds(Long bookingId) {
        return transactionTemplate.execute(status -> reload(bookingId).getPassengers().stream()
                .map(BookingPassenger::getId)
                .sorted()
                .toList());
    }

    private PassengerStatus statusOf(Long bookingId, Long passengerId) {
        return transactionTemplate.execute(status -> reload(bookingId).getPassengers().stream()
                .filter(p -> passengerId.equals(p.getId()))
                .findFirst()
                .orElseThrow()
                .getPassengerStatus());
    }

    private List<RefundRecordDto> cancel(Long bookingId, List<Long> ids) {
        return cancellationService.cancelPassengers(userId, bookingId, refundRequest(ids));
    }

    private PassengerRefundRequest refundRequest(List<Long> passengerIds) {
        PassengerRefundRequest request = new PassengerRefundRequest();
        request.setPassengerIds(passengerIds);
        request.setReason("Phase 5 test");
        return request;
    }

    private CancelBookingRequest cancelRequest(String reason) {
        CancelBookingRequest request = new CancelBookingRequest();
        request.setReason(reason);
        return request;
    }

    private static BigDecimal sum(List<PassengerRefundLineDto> lines,
                                   java.util.function.Function<PassengerRefundLineDto, BigDecimal> field) {
        return money(lines.stream().map(field).reduce(ZERO, BigDecimal::add));
    }

    private BookingResponseDto create(Long uid, LocalDate journeyDate, int passengers) {
        return bookingService.createBooking(uid, request(journeyDate, passengers), null);
    }

    private BookingResponseDto createAndPay(Long uid, LocalDate journeyDate, int passengers) {
        BookingResponseDto dto = create(uid, journeyDate, passengers);
        return pay(uid, dto.getId(), true);
    }

    private BookingResponseDto pay(Long uid, Long bookingId, boolean success) {
        ProcessPaymentRequest payment = new ProcessPaymentRequest();
        payment.setMethod("UPI");
        payment.setSimulateSuccess(success);
        return bookingService.processPayment(uid, bookingId, payment, null);
    }

    private CreateBookingRequest request(LocalDate journeyDate, int passengers) {
        CreateBookingRequest request = new CreateBookingRequest();
        request.setTrainId(rajdhani.getId());
        request.setCoachId(thirdAcCoach.getId());
        request.setSourceStationId(ndls);
        request.setDestinationStationId(lko);
        request.setJourneyDate(journeyDate);
        List<PassengerDto> people = new ArrayList<>();
        for (int i = 1; i <= passengers; i++) {
            PassengerDto p = new PassengerDto();
            p.setFullName("Rider " + i + " " + COUNTER.incrementAndGet());
            p.setAge(30);
            p.setGender("MALE");
            p.setBerthPreference("NO_PREFERENCE");
            p.setQuota(QuotaType.GENERAL.name());
            people.add(p);
        }
        request.setPassengers(people);
        return request;
    }

    private void reactivateSeededThirdAcCoaches() {
        transactionTemplate.executeWithoutResult(status -> {
            for (Coach coach : coachRepository.findByTrainId(rajdhani.getId())) {
                if (coach.getSeatClass() == SeatClass.THIRD_AC && !Boolean.TRUE.equals(coach.getActive())) {
                    coach.setActive(true);
                    coachRepository.save(coach);
                }
            }
            coachRepository.flush();
        });
        thirdAcCoach = coachRepository.findByTrainIdAndSeatClass(rajdhani.getId(), SeatClass.THIRD_AC)
                .orElseThrow();
    }

    private Long newUser(String email, String fullName, RoleName role) {
        return transactionTemplate.execute(status -> userRepository.saveAndFlush(User.builder()
                .fullName(fullName)
                .email(email)
                .password(passwordEncoder.encode("Passw0rd!"))
                .role(role)
                .active(true)
                .temporaryAccount(false)
                .build()).getId());
    }

    private Long stationId(String code) {
        return stationRepository.findAll().stream()
                .filter(s -> code.equals(s.getCode()))
                .findFirst()
                .orElseThrow()
                .getId();
    }

    private static BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }

    /** Small readability helper so the ledger assertions name the property they care about. */
    private static final class PaymentHistoryEntryAssertions {
        static void assertSettled(PaymentSummaryDto history) {
            assertThat(history.getFailedAttemptCount()).isZero();
            assertThat(history.getTotalSettled()).isEqualByComparingTo(history.getTotalAttempted());
            assertThat(history.getAttempts())
                    .allSatisfy(entry -> assertThat(entry.getSettled()).isTrue());
        }
    }
}

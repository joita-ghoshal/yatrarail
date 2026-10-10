package com.yatrarail;

import com.yatrarail.dto.booking.*;
import com.yatrarail.dto.train.ClassAvailability;
import com.yatrarail.dto.train.TrainSearchRequest;
import com.yatrarail.dto.train.TrainSearchResult;
import com.yatrarail.entity.*;
import com.yatrarail.exception.BadRequestException;
import com.yatrarail.exception.ConflictException;
import com.yatrarail.exception.ResourceNotFoundException;
import com.yatrarail.repository.*;
import com.yatrarail.service.BookingService;
import com.yatrarail.service.SeatAllocationService;
import com.yatrarail.service.TrainService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 2 reservation engine, exercised against a real (in-memory) database rather than mocks, so
 * the seat-hold uniqueness constraint, the coach lock and the FIFO queue are all genuinely tested.
 * <p>
 * Deliberately NOT wrapped in a test-wide transaction: each service call gets its own transaction,
 * which is how it behaves in production and is the only way to really test the locking and flush
 * behaviour. State is reset in {@link #setUp()} instead.
 */
@SpringBootTest
@ActiveProfiles("test")
class BookingReservationIntegrationTest {

    private static final AtomicInteger COUNTER = new AtomicInteger();

    /** Coach codes for coaches this test creates for itself; they are torn down after every test. */
    private static final String TEST_COACH_PREFIX = "ZQ";

    @Autowired
    private BookingService bookingService;
    @Autowired
    private SeatAllocationService seatAllocationService;
    @Autowired
    private TrainService trainService;
    @Autowired
    private BookingRepository bookingRepository;
    @Autowired
    private SeatReservationRepository seatReservationRepository;
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
    private Long pnbe;
    private Long hwh;
    private LocalDate journeyDate;

    @BeforeEach
    void setUp() {
        resetBookingState();

        int n = COUNTER.incrementAndGet();
        userId = newUser("res" + n + "@example.com", "Reservation Tester");
        otherUserId = newUser("other" + n + "@example.com", "Other Tester");

        rajdhani = trainRepository.findByTrainNumber("12301").orElseThrow();
        thirdAcCoach = coachRepository.findByTrainIdAndSeatClass(rajdhani.getId(), SeatClass.THIRD_AC)
                .orElseThrow();
        // A test that closes the coach for bookings must not leak that into the next one.
        if (!Boolean.TRUE.equals(thirdAcCoach.getActive())) {
            thirdAcCoach.setActive(true);
            coachRepository.saveAndFlush(thirdAcCoach);
        }
        ndls = stationId("NDLS");
        lko = stationId("LKO");
        pnbe = stationId("PNBE");
        hwh = stationId("HWH");

        // The seeded train runs daily, so tomorrow is always a service day.
        journeyDate = LocalDate.now().plusDays(1);
    }

    /** Booking state is global; wipe it so each test starts from a full coach. */
    private void resetBookingState() {
        transactionTemplate.executeWithoutResult(status -> {
            cancellationRepository.deleteAll();
            paymentAttemptRepository.deleteAll();
            paymentRepository.deleteAll();
            seatReservationRepository.deleteAll();
            bookingRepository.deleteAll();
            coachRepository.findAll().stream()
                    .filter(c -> c.getCoachCode() != null && c.getCoachCode().startsWith(TEST_COACH_PREFIX))
                    .collect(Collectors.toList())
                    .forEach(coach -> {
                        seatRepository.deleteByCoachId(coach.getId());
                        coachRepository.delete(coach);
                    });
            seatRepository.flush();
            coachRepository.flush();
        });
    }

    // ------------------------------------------------------------------ holds

    @Test
    @DisplayName("creating a booking holds a seat in HELD state and stays PAYMENT_PENDING")
    void createBookingHoldsTheSeat() {
        BookingResponseDto dto = create(userId, request(ndls, pnbe, 1));

        assertThat(dto.getStatus()).isEqualTo("PAYMENT_PENDING");
        assertThat(dto.getPaymentDeadline()).isNotNull();
        assertThat(dto.getPassengers()).singleElement().satisfies(p -> {
            assertThat(p.getPassengerStatus()).isEqualTo("CONFIRMED");
            assertThat(p.getSeatNumber()).isNotNull();
        });

        assertThat(reservationsForJourneyDate()).singleElement().satisfies(r -> {
            assertThat(r.getStatus()).isEqualTo(ReservationStatus.HELD);
            assertThat(r.getExpiresAt()).isNotNull();
        });
    }

    @Test
    @DisplayName("a successful payment converts the hold into a CONFIRMED reservation")
    void paymentConfirmsTheHold() {
        BookingResponseDto created = create(userId, request(ndls, pnbe, 1));
        BookingResponseDto paid = pay(userId, created.getId(), true, null);

        assertThat(paid.getStatus()).isEqualTo("CONFIRMED");
        assertThat(paid.getPaymentDeadline()).isNull();
        assertThat(paid.getPayment()).satisfies(p -> {
            assertThat(p.getStatus()).isEqualTo("SUCCESS");
            assertThat(p.getAmount()).isEqualByComparingTo(paid.getTotalFare());
        });

        assertThat(reservationsForJourneyDate()).singleElement().satisfies(r -> {
            assertThat(r.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
            assertThat(r.getExpiresAt()).isNull();
        });
    }

    @Test
    @DisplayName("a failed payment marks the booking PAYMENT_FAILED but keeps the hold for a retry")
    void failedPaymentKeepsTheHold() {
        BookingResponseDto created = create(userId, request(ndls, pnbe, 1));
        BookingResponseDto failed = pay(userId, created.getId(), false, null);

        assertThat(failed.getStatus()).isEqualTo("PAYMENT_FAILED");
        assertThat(failed.getPayment().getStatus()).isEqualTo("FAILED");

        // The seat is still reserved so the passenger can retry without losing it.
        assertThat(reservationsForJourneyDate()).singleElement()
                .satisfies(r -> assertThat(r.getStatus()).isEqualTo(ReservationStatus.HELD));

        // ...and a retry succeeds.
        assertThat(pay(userId, created.getId(), true, null).getStatus()).isEqualTo("CONFIRMED");
    }

    // ------------------------------------------------------------------ duplicate prevention

    @Test
    @DisplayName("a second passenger never gets a seat that is already held")
    void twoPassengersNeverShareASeat() {
        BookingResponseDto first = create(userId, request(ndls, pnbe, 1));
        BookingResponseDto second = create(otherUserId, request(ndls, pnbe, 1));

        assertThat(first.getPassengers().get(0).getSeatNumber())
                .isNotEqualTo(second.getPassengers().get(0).getSeatNumber());
    }

    @Test
    @DisplayName("the database rejects a duplicate hold for the same seat and date")
    void duplicateHoldForTheSameSeatAndDateIsImpossible() {
        create(userId, request(ndls, pnbe, 1));
        SeatReservation existing = reservationsForJourneyDate().get(0);

        // Bypass the service layer entirely: this is the DB-level backstop the service relies on.
        SeatReservation duplicate = SeatReservation.builder()
                .seat(existing.getSeat())
                .bookingPassenger(existing.getBookingPassenger())
                .journeyDate(journeyDate)
                .status(ReservationStatus.HELD)
                .expiresAt(LocalDateTime.now().plusMinutes(10))
                .coachId(thirdAcCoach.getId())
                .trainId(rajdhani.getId())
                .build();

        assertThatThrownBy(() -> seatReservationRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("one passenger cannot hold two live bookings on the same train and date")
    void duplicateBookingForTheSameUserIsRejected() {
        create(userId, request(ndls, pnbe, 1));

        assertThatThrownBy(() -> bookingService.createBooking(userId, request(ndls, pnbe, 1), null))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already have a live booking");
    }

    @Test
    @DisplayName("after cancelling, the same passenger may book the train again")
    void cancellingFreesTheUserToBookAgain() {
        BookingResponseDto first = create(userId, request(ndls, pnbe, 1));
        pay(userId, first.getId(), true, null);
        bookingService.cancelBooking(userId, first.getId(), cancelRequest());

        assertThat(create(userId, request(ndls, pnbe, 1)).getId()).isNotEqualTo(first.getId());
    }

    // ------------------------------------------------------------------ idempotency

    @Test
    @DisplayName("replaying a booking Idempotency-Key returns the original booking, not a second one")
    void bookingCreationIsIdempotent() {
        String key = "create-key-" + COUNTER.incrementAndGet();

        BookingResponseDto first = bookingService.createBooking(userId, request(ndls, pnbe, 1), key);
        BookingResponseDto replay = bookingService.createBooking(userId, request(ndls, pnbe, 1), key);

        assertThat(replay.getId()).isEqualTo(first.getId());
        assertThat(replay.getBookingReference()).isEqualTo(first.getBookingReference());
        assertThat(bookingRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("another user's booking is never revealed by a replayed key")
    void idempotencyKeyDoesNotLeakAnotherUsersBooking() {
        String key = "shared-key-" + COUNTER.incrementAndGet();
        create(userId, request(ndls, pnbe, 1), key);

        assertThatThrownBy(() -> bookingService.createBooking(otherUserId, request(ndls, pnbe, 1), key))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already been submitted");
    }

    @Test
    @DisplayName("replaying a payment Idempotency-Key never creates a second transaction")
    void paymentIsIdempotent() {
        BookingResponseDto created = create(userId, request(ndls, pnbe, 1));
        String key = "pay-key-" + COUNTER.incrementAndGet();

        BookingResponseDto first = pay(userId, created.getId(), true, key);
        BookingResponseDto replay = pay(userId, created.getId(), true, key);

        assertThat(replay.getId()).isEqualTo(first.getId());
        assertThat(replay.getPayment().getTransactionReference())
                .isEqualTo(first.getPayment().getTransactionReference());
        assertThat(paymentRepository.findByBookingId(created.getId())).isPresent();
    }

    @Test
    @DisplayName("paying an already-paid booking is rejected")
    void doublePaymentIsRejected() {
        BookingResponseDto created = create(userId, request(ndls, pnbe, 1));
        pay(userId, created.getId(), true, null);

        assertThatThrownBy(() -> bookingService.processPayment(userId, created.getId(), paymentRequest(true), null))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("not awaiting payment");
    }

    // ------------------------------------------------------------------ expiry

    @Test
    @DisplayName("an unpaid booking past its deadline is expired and its seat released")
    void expiredBookingReleasesItsSeat() {
        BookingResponseDto created = create(userId, request(ndls, pnbe, 1));
        Integer seat = created.getPassengers().get(0).getSeatNumber();
        assertThat(occupiedSeatCount(seat)).isEqualTo(1);

        expire(created.getId());

        assertThat(bookingRepository.findById(created.getId()).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.EXPIRED);
        assertThat(bookingService.getBookingDetails(userId, created.getId()).getPassengers())
                .allSatisfy(p -> assertThat(p.getPassengerStatus()).isEqualTo("EXPIRED"));
        assertThat(occupiedSeatCount(seat)).isZero();
    }

    @Test
    @DisplayName("expiry never touches a booking that is still inside its payment window")
    void expiryIgnoresBookingsInsideTheWindow() {
        BookingResponseDto created = create(userId, request(ndls, pnbe, 1));

        assertThat(seatAllocationService.expireBooking(created.getId())).isFalse();
        assertThat(bookingRepository.findById(created.getId()).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.PAYMENT_PENDING);
    }

    @Test
    @DisplayName("expiry is idempotent")
    void expiryIsIdempotent() {
        BookingResponseDto created = create(userId, request(ndls, pnbe, 1));
        expire(created.getId());

        assertThat(seatAllocationService.expireBooking(created.getId())).isFalse();
        assertThat(seatAllocationService.expireBooking(created.getId())).isFalse();
    }

    @Test
    @DisplayName("an expired booking cannot be paid, and the freed seat is sellable again")
    void expiredBookingCannotBePaidButFreesInventory() {
        BookingResponseDto created = create(userId, request(ndls, pnbe, 1));
        expire(created.getId());

        assertThatThrownBy(() -> bookingService.processPayment(
                userId, created.getId(), paymentRequest(true), null))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("expired");

        // The seat is back on sale to somebody else.
        assertThat(create(otherUserId, request(ndls, pnbe, 1)).getPassengers().get(0).getPassengerStatus())
                .isEqualTo("CONFIRMED");
    }

    // ------------------------------------------------------------------ RAC + waitlist

    @Test
    @DisplayName("a full coach spills over to RAC")
    void overflowGoesToRac() {
        fillCoach();

        BookingResponseDto racBooking = create(scratchUser(), request(ndls, pnbe, 1));
        assertThat(racBooking.getPassengers().get(0).getPassengerStatus()).isEqualTo("RAC");
        assertThat(racBooking.getPassengers().get(0).getSeatNumber()).isNull();

        pay(scratchUserOf(racBooking), racBooking.getId(), true, null);
        assertThat(bookingRepository.findById(racBooking.getId()).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.RAC);
    }

    @Test
    @DisplayName("once RAC capacity is used up, further passengers go to the waitlist with a queue position")
    void beyondRacCapacityGoesToTheWaitlist() {
        fillCoach();

        Long racUser = scratchUser();
        BookingResponseDto racBooking = create(racUser, request(ndls, pnbe, 1));
        pay(racUser, racBooking.getId(), true, null);
        assertThat(racBooking.getPassengers().get(0).getPassengerStatus()).isEqualTo("RAC");

        // Fill the remaining RAC slots, then the next booking has to wait.
        IntStream.range(0, thirdAcCoach.getRacCapacity())
                .forEach(i -> {
                    Long uid = scratchUser();
                    BookingResponseDto dto = create(uid, request(ndls, pnbe, 1));
                    pay(uid, dto.getId(), true, null);
                });

        BookingResponseDto waitlisted = create(scratchUser(), request(ndls, pnbe, 1));
        assertThat(waitlisted.getPassengers().get(0).getPassengerStatus()).isEqualTo("WAITLISTED");
        assertThat(waitlisted.getPassengers().get(0).getWaitlistPosition()).isNotNull();
    }

    @Test
    @DisplayName("declining RAC puts the passenger straight on the waitlist")
    void decliningRacGoesStraightToTheWaitlist() {
        fillCoach();

        CreateBookingRequest request = request(ndls, pnbe, 1);
        request.setAllowRac(false);
        BookingResponseDto dto = bookingService.createBooking(scratchUser(), request, null);

        assertThat(dto.getPassengers().get(0).getPassengerStatus()).isEqualTo("WAITLISTED");
    }

    @Test
    @DisplayName("cancelling a confirmed booking promotes the waitlist in FIFO order")
    void cancellationPromotesTheWaitlistInOrder() {
        // 3 seats, no RAC: so a freed seat can only go to the waitlist.
        Coach coach = newTestCoach("ZQT", 3, 0);
        List<Long> holders = fillAndPay(coach);

        Long firstWaitlisted = scratchUser();
        Long secondWaitlisted = scratchUser();
        BookingResponseDto wait1 = createAndPay(firstWaitlisted, request(coach, ndls, pnbe, 1));
        BookingResponseDto wait2 = createAndPay(secondWaitlisted, request(coach, ndls, pnbe, 1));
        assertThat(wait1.getPassengers().get(0).getPassengerStatus()).isEqualTo("WAITLISTED");
        assertThat(wait2.getPassengers().get(0).getPassengerStatus()).isEqualTo("WAITLISTED");
        assertThat(wait1.getPassengers().get(0).getWaitlistPosition())
                .isLessThan(wait2.getPassengers().get(0).getWaitlistPosition());
        assertThat(wait1.getStatus()).isEqualTo("WAITLISTED");

        // Cancel one confirmed booking: exactly one seat comes back, and the first in line takes it.
        Long canceller = holders.get(0);
        bookingService.cancelBooking(canceller, findBookingId(canceller, BookingStatus.CONFIRMED), cancelRequest());

        BookingResponseDto promoted = bookingService.getBookingDetails(firstWaitlisted, wait1.getId());
        BookingResponseDto stillWaiting = bookingService.getBookingDetails(secondWaitlisted, wait2.getId());
        assertThat(promoted.getPassengers().get(0).getPassengerStatus()).isEqualTo("CONFIRMED");
        assertThat(promoted.getPassengers().get(0).getSeatNumber()).isNotNull();
        assertThat(promoted.getStatus()).isEqualTo("CONFIRMED");
        assertThat(stillWaiting.getPassengers().get(0).getPassengerStatus()).isEqualTo("WAITLISTED");
    }

    @Test
    @DisplayName("an existing RAC entry is upgraded to its own seat before the waitlist is served")
    void racIsPromotedBeforeTheWaitlist() {
        // 2 seats and a single RAC slot: 2 confirmed, 1 on RAC, 1 waitlisted.
        Coach coach = newTestCoach("ZQR", 2, 1);
        List<Long> holders = fillAndPay(coach);

        Long racUser = scratchUser();
        BookingResponseDto racBooking = createAndPay(racUser, request(coach, ndls, pnbe, 1));
        assertThat(racBooking.getPassengers().get(0).getPassengerStatus()).isEqualTo("RAC");

        Long waiting = scratchUser();
        BookingResponseDto waitlisted = createAndPay(waiting, request(coach, ndls, pnbe, 1));
        assertThat(waitlisted.getPassengers().get(0).getPassengerStatus()).isEqualTo("WAITLISTED");

        // The freed seat goes to the RAC passenger, who was ahead in the queue.
        Long canceller = holders.get(0);
        bookingService.cancelBooking(canceller, findBookingId(canceller, BookingStatus.CONFIRMED), cancelRequest());

        assertThat(bookingService.getBookingDetails(racUser, racBooking.getId())
                .getPassengers().get(0).getPassengerStatus()).isEqualTo("CONFIRMED");
        assertThat(bookingService.getBookingDetails(waiting, waitlisted.getId())
                .getPassengers().get(0).getPassengerStatus()).isEqualTo("WAITLISTED");
    }

    @Test
    @DisplayName("an unpaid waitlisted booking is not promoted before it pays")
    void unpaidBookingsAreNotPromoted() {
        Coach coach = newTestCoach("ZQU", 2, 0);
        List<Long> holders = fillAndPay(coach);

        Long waiting = scratchUser();
        BookingResponseDto unpaid = create(waiting, request(coach, ndls, pnbe, 1));
        assertThat(unpaid.getPassengers().get(0).getPassengerStatus()).isEqualTo("WAITLISTED");

        Long canceller = holders.get(0);
        bookingService.cancelBooking(canceller, findBookingId(canceller, BookingStatus.CONFIRMED), cancelRequest());

        // Still unpaid, so still waitlisted: paying is what earns a place in the queue.
        assertThat(bookingService.getBookingDetails(waiting, unpaid.getId())
                .getPassengers().get(0).getPassengerStatus()).isEqualTo("WAITLISTED");
    }

    // ------------------------------------------------------------------ segment fare

    @Test
    @DisplayName("a booking is priced for the booked segment, not the whole route")
    void fareIsSegmentBased() {
        BookingQuoteDto fullRoute = quote(ndls, hwh, 1);
        BookingQuoteDto subSegment = quote(ndls, lko, 1);

        assertThat(fullRoute.getDistanceKm()).isEqualTo(1450);
        assertThat(subSegment.getDistanceKm()).isEqualTo(445);
        assertThat(subSegment.getFarePerPassenger()).isLessThan(fullRoute.getFarePerPassenger());
        assertThat(subSegment.getTotalFare()).isLessThan(fullRoute.getTotalFare());
    }

    @Test
    @DisplayName("the booked booking records the same segment fare the quote promised")
    void createdBookingMatchesTheQuote() {
        BookingQuoteDto quoted = quote(ndls, lko, 2);
        BookingResponseDto created = create(userId, request(ndls, lko, 2));

        assertThat(created.getDistanceKm()).isEqualTo(quoted.getDistanceKm());
        assertThat(created.getFarePerPassenger()).isEqualByComparingTo(quoted.getFarePerPassenger());
        assertThat(created.getTotalFare()).isEqualByComparingTo(quoted.getTotalFare());
    }

    @Test
    @DisplayName("total fare is the itemised sum: passenger fares, then platform fee, then GST")
    void totalFareScalesWithPassengerCount() {
        BookingQuoteDto one = quote(ndls, hwh, 1);
        BookingQuoteDto three = quote(ndls, hwh, 3);

        // Phase 4: the grand total is no longer gross Ã— count. The passenger fares scale, and the
        // platform fee is charged once per booking while GST is charged on the subtotal.
        assertThat(three.getFareBreakdown().getPassengerFareTotal())
                .isEqualByComparingTo(one.getFareBreakdown().getPassengerFareTotal()
                        .multiply(BigDecimal.valueOf(3)));
        assertThat(three.getFareBreakdown().getPlatformFee())
                .isEqualByComparingTo(one.getFareBreakdown().getPlatformFee());
        assertThat(three.getFareBreakdown().getGstAmount())
                .isEqualByComparingTo(one.getFareBreakdown().getGstAmount()
                        .multiply(BigDecimal.valueOf(3)));

        assertThat(three.getTotalFare()).isEqualByComparingTo(
                three.getFareBreakdown().getPassengerFareTotal()
                        .add(three.getFareBreakdown().getPlatformFee())
                        .add(three.getFareBreakdown().getGstAmount()));
        assertThat(three.getTotalFare()).isGreaterThan(one.getTotalFare());
    }

    // ------------------------------------------------------------------ segment search

    @Test
    @DisplayName("search finds a train for a mid-route segment, not just origin-to-terminus services")
    void searchFindsMidRouteSegments() {
        List<TrainSearchResult> results = trainService.search(search(ndls, lko));

        assertThat(results).extracting(TrainSearchResult::getTrainNumber).contains("12301");
        TrainSearchResult leg = results.stream()
                .filter(r -> "12301".equals(r.getTrainNumber()))
                .findFirst()
                .orElseThrow();
        assertThat(leg.getDistanceKm()).isEqualTo(445);
        assertThat(leg.getSourceStationId()).isEqualTo(ndls);
        assertThat(leg.getDestinationStationId()).isEqualTo(lko);
    }

    @Test
    @DisplayName("search prices the segment it was asked for")
    void searchIsPricedForTheSegment() {
        BigDecimal fullRoute = fareFromSearch(trainService.search(search(ndls, hwh)));
        BigDecimal subSegment = fareFromSearch(trainService.search(search(ndls, lko)));

        assertThat(subSegment).isLessThan(fullRoute);
    }

    @Test
    @DisplayName("search does not return a train that runs the segment the wrong way round")
    void searchExcludesReversedSegments() {
        assertThat(trainService.search(search(lko, ndls)))
                .extracting(TrainSearchResult::getTrainNumber)
                .doesNotContain("12301");
    }

    @Test
    @DisplayName("search excludes a train that does not stop at the requested station")
    void searchExcludesTrainsNotCallingAtTheStation() {
        assertThat(trainService.search(search(ndls, stationId("BCT"))))
                .extracting(TrainSearchResult::getTrainNumber)
                .doesNotContain("12301");
    }

    @Test
    @DisplayName("a seat class filter keeps only trains offering that class")
    void searchFiltersBySeatClass() {
        List<TrainSearchResult> results = trainService.search(searchWithClass(ndls, hwh, SeatClass.FIRST_AC));

        assertThat(results).isNotEmpty();
        assertThat(results).allSatisfy(r -> assertThat(r.getClassAvailability())
                .extracting(ClassAvailability::getSeatClass)
                .contains(SeatClass.FIRST_AC.name()));
    }

    // ------------------------------------------------------------------ validation

    @Test
    @DisplayName("stations in the wrong travel order are rejected")
    void reverseOrderIsRejected() {
        assertThatThrownBy(() -> create(userId, request(pnbe, lko, 1)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("correct travel order");
    }

    @Test
    @DisplayName("a station the train does not stop at is rejected")
    void stationOffTheRouteIsRejected() {
        assertThatThrownBy(() -> create(userId, request(ndls, stationId("BCT"), 1)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("does not stop");
    }

    @Test
    @DisplayName("a coach belonging to a different train is rejected")
    void coachFromAnotherTrainIsRejected() {
        Coach foreign = coachRepository.findByTrainIdAndSeatClass(
                        trainRepository.findByTrainNumber("12951").orElseThrow().getId(), SeatClass.THIRD_AC)
                .orElseThrow();
        CreateBookingRequest request = request(ndls, pnbe, 1);
        request.setCoachId(foreign.getId());

        assertThatThrownBy(() -> bookingService.createBooking(userId, request, null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("does not belong to this train");
    }

    @Test
    @DisplayName("a date the train does not run is rejected")
    void nonServiceDayIsRejected() {
        // 12138 Punjab Mail runs TUE,THU,SAT,SUN; Monday is not a service day.
        Train punjabMail = trainRepository.findByTrainNumber("12138").orElseThrow();
        CreateBookingRequest request = request(stationId("PUNE"), stationId("JP"), 1);
        request.setTrainId(punjabMail.getId());
        request.setCoachId(coachRepository.findByTrainIdAndSeatClass(punjabMail.getId(), SeatClass.THIRD_AC)
                .orElseThrow().getId());
        request.setJourneyDate(nextDayOf(DayOfWeek.MONDAY));

        assertThatThrownBy(() -> bookingService.createBooking(userId, request, null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("does not run on");
    }

    @Test
    @DisplayName("an inactive coach is closed for bookings")
    void inactiveCoachIsRejected() {
        thirdAcCoach.setActive(false);
        coachRepository.saveAndFlush(thirdAcCoach);

        assertThatThrownBy(() -> create(userId, request(ndls, pnbe, 1)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("closed for bookings");
    }

    @Test
    @DisplayName("a past journey date is rejected")
    void pastJourneyDateIsRejected() {
        CreateBookingRequest request = request(ndls, pnbe, 1);
        request.setJourneyDate(LocalDate.now().minusDays(1));

        assertThatThrownBy(() -> bookingService.createBooking(userId, request, null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("cannot be in the past");
    }

    @Test
    @DisplayName("a date beyond the advance-booking window is rejected")
    void tooFarAheadIsRejected() {
        CreateBookingRequest request = request(ndls, pnbe, 1);
        request.setJourneyDate(LocalDate.now().plusDays(
                com.yatrarail.service.TrainScheduleValidator.MAX_ADVANCE_DAYS + 1));

        assertThatThrownBy(() -> bookingService.createBooking(userId, request, null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("days in advance");
    }

    @Test
    @DisplayName("another user cannot read, pay or cancel somebody else's booking")
    void bookingsAreScopedToTheirOwner() {
        BookingResponseDto created = create(userId, request(ndls, pnbe, 1));

        assertThatThrownBy(() -> bookingService.getBookingDetails(otherUserId, created.getId()))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> bookingService.processPayment(
                otherUserId, created.getId(), paymentRequest(true), null))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> bookingService.cancelBooking(otherUserId, created.getId(), cancelRequest()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("duplicate passenger names in one booking are rejected")
    void duplicatePassengerNamesAreRejected() {
        CreateBookingRequest request = request(ndls, pnbe, 2);
        request.getPassengers().get(1).setFullName(request.getPassengers().get(0).getFullName());

        assertThatThrownBy(() -> bookingService.createBooking(userId, request, null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("same name as passenger");
    }

    // ------------------------------------------------------------------ helpers

    private Long stationId(String code) {
        return stationRepository.findByCode(code).orElseThrow().getId();
    }

    private Long newUser(String email, String fullName) {
        return userRepository.save(User.builder()
                .fullName(fullName)
                .email(email)
                .phone("90" + String.format("%08d", Math.abs(email.hashCode()) % 100000000))
                .password(passwordEncoder.encode("Password@123"))
                .role(RoleName.PASSENGER)
                .active(true)
                .build())
                .getId();
    }

    /** A throwaway user, so each test can book as many distinct passengers as it needs. */
    private Long scratchUser() {
        return newUser("scratch" + COUNTER.incrementAndGet() + "-" + System.nanoTime() + "@example.com",
                "Scratch " + COUNTER.incrementAndGet());
    }

    private Long scratchUserOf(BookingResponseDto dto) {
        return bookingRepository.findById(dto.getId()).orElseThrow().getUser().getId();
    }

    private CreateBookingRequest request(Long source, Long destination, int passengers) {
        return request(thirdAcCoach, source, destination, passengers);
    }

    private CreateBookingRequest request(Coach coach, Long source, Long destination, int passengers) {
        CreateBookingRequest request = new CreateBookingRequest();
        request.setTrainId(rajdhani.getId());
        request.setCoachId(coach.getId());
        request.setSourceStationId(source);
        request.setDestinationStationId(destination);
        request.setJourneyDate(journeyDate);
        request.setPassengers(IntStream.range(0, passengers)
                .mapToObj(i -> passenger("Passenger " + (char) ('A' + i)))
                .toList());
        return request;
    }

    /**
     * A throwaway coach on the seeded train, so queue behaviour can be tested against a known,
     * tiny inventory instead of the 64-seat demo coach.
     */
    private Coach newTestCoach(String prefix, int seats, int racCapacity) {
        Coach coach = coachRepository.save(Coach.builder()
                .train(rajdhani)
                .coachCode(prefix + COUNTER.incrementAndGet())
                .seatClass(SeatClass.THIRD_AC)
                .totalSeats(seats)
                .baseFare(new BigDecimal("950"))
                .racCapacity(racCapacity)
                .active(true)
                .build());
        for (int i = 1; i <= seats; i++) {
            seatRepository.save(Seat.builder()
                    .coach(coach)
                    .seatNumber(i)
                    .berthType(i % 2 == 0 ? "WINDOW" : "AISLE")
                    .active(true)
                    .build());
        }
        return coach;
    }

    private PassengerDto passenger(String name) {
        PassengerDto dto = new PassengerDto();
        dto.setFullName(name);
        dto.setAge(30);
        dto.setGender("MALE");
        dto.setBerthPreference("NO_PREFERENCE");
        return dto;
    }

    private ProcessPaymentRequest paymentRequest(boolean success) {
        ProcessPaymentRequest request = new ProcessPaymentRequest();
        request.setMethod("UPI");
        request.setSimulateSuccess(success);
        return request;
    }

    private CancelBookingRequest cancelRequest() {
        CancelBookingRequest request = new CancelBookingRequest();
        request.setReason("Integration test cancellation");
        return request;
    }

    private BookingQuoteRequest quoteRequest(Long source, Long destination, int passengers) {
        BookingQuoteRequest request = new BookingQuoteRequest();
        request.setTrainId(rajdhani.getId());
        request.setCoachId(thirdAcCoach.getId());
        request.setSourceStationId(source);
        request.setDestinationStationId(destination);
        request.setJourneyDate(journeyDate);
        request.setPassengers(passengers);
        return request;
    }

    private BookingResponseDto create(Long uid, CreateBookingRequest request) {
        return bookingService.createBooking(uid, request, null);
    }

    private BookingResponseDto create(Long uid, CreateBookingRequest request, String key) {
        return bookingService.createBooking(uid, request, key);
    }

    private BookingResponseDto pay(Long uid, Long bookingId, boolean success, String key) {
        return bookingService.processPayment(uid, bookingId, paymentRequest(success), key);
    }

    /** Books and immediately pays, which is the only way a passenger joins the promotion queue. */
    private BookingResponseDto createAndPay(Long uid, CreateBookingRequest request) {
        BookingResponseDto dto = bookingService.createBooking(uid, request, null);
        return bookingService.processPayment(uid, dto.getId(), paymentRequest(true), null);
    }

    /** Fills every seat of a coach with a separate, paid, one-passenger booking. */
    private List<Long> fillAndPay(Coach coach) {
        return IntStream.range(0, coach.getTotalSeats())
                .mapToObj(i -> scratchUser())
                .peek(uid -> createAndPay(uid, request(coach, ndls, pnbe, 1)))
                .toList();
    }

    private BookingQuoteDto quote(Long source, Long destination, int passengers) {
        // Phase 4: the quote needs the caller's role, because eligibility for a reserved quota is
        // decided server-side. These tests book as an ordinary passenger.
        return bookingService.quote(quoteRequest(source, destination, passengers), RoleName.PASSENGER);
    }

    private TrainSearchRequest search(Long source, Long destination) {
        return searchWithClass(source, destination, null);
    }

    private TrainSearchRequest searchWithClass(Long source, Long destination, SeatClass seatClass) {
        TrainSearchRequest request = new TrainSearchRequest();
        request.setSourceCode(stationRepository.findById(source).orElseThrow().getCode());
        request.setDestinationCode(stationRepository.findById(destination).orElseThrow().getCode());
        request.setJourneyDate(journeyDate);
        request.setSeatClass(seatClass == null ? null : seatClass.name());
        return request;
    }

    /** Cheapest third-AC fare offered across the results, used to compare segment pricing. */
    private BigDecimal fareFromSearch(List<TrainSearchResult> results) {
        return results.stream()
                .filter(r -> "12301".equals(r.getTrainNumber()))
                .flatMap(r -> r.getClassAvailability().stream())
                .filter(c -> SeatClass.THIRD_AC.name().equals(c.getSeatClass()))
                .map(ClassAvailability::getFare)
                .min(BigDecimal::compareTo)
                .orElseThrow();
    }

    /** Back-date the payment window, then run the expiry exactly as the scheduled job would. */
    private void expire(Long bookingId) {
        Booking booking = bookingRepository.findById(bookingId).orElseThrow();
        booking.setPaymentDeadline(LocalDateTime.now().minusMinutes(1));
        bookingRepository.saveAndFlush(booking);
        seatAllocationService.expireBooking(bookingId);
    }

    /** Books one passenger per seat, leaving the coach completely full. */
    private void fillCoach() {
        IntStream.range(0, thirdAcCoach.getTotalSeats())
                .forEach(i -> create(scratchUser(), request(ndls, pnbe, 1)));
    }

    private LocalDate nextDayOf(DayOfWeek day) {
        LocalDate date = LocalDate.now().plusDays(1);
        while (date.getDayOfWeek() != day) {
            date = date.plusDays(1);
        }
        return date;
    }

    private Long findBookingId(Long uid, BookingStatus status) {
        return bookingRepository.findByUserIdOrderByCreatedAtDesc(uid).stream()
                .filter(b -> b.getStatus() == status)
                .findFirst()
                .orElseThrow()
                .getId();
    }

    private List<SeatReservation> reservationsForJourneyDate() {
        return seatReservationRepository.findAll().stream()
                .filter(r -> r.getJourneyDate().equals(journeyDate))
                .toList();
    }

    private long occupiedSeatCount(Integer seatNumber) {
        Long seatId = seatRepository.findByCoachIdAndActiveTrueOrderBySeatNumberAsc(thirdAcCoach.getId())
                .stream()
                .filter(s -> s.getSeatNumber().equals(seatNumber))
                .findFirst()
                .orElseThrow()
                .getId();
        return seatReservationRepository.findOccupiedSeatIdsForCoach(
                        thirdAcCoach.getId(), journeyDate, LocalDateTime.now())
                .stream()
                .filter(seatId::equals)
                .count();
    }
}

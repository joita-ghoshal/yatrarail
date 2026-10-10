package com.yatrarail;

import com.yatrarail.dto.booking.CreateBookingRequest;
import com.yatrarail.dto.booking.PassengerDto;
import com.yatrarail.entity.*;
import com.yatrarail.repository.*;
import com.yatrarail.service.BookingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reservation engine's real concurrency guarantee, tested with genuinely parallel threads and
 * real database transactions (H2, PostgreSQL compatibility mode).
 * <p>
 * Every thread runs its own transaction, so the {@code SELECT ... FOR UPDATE} coach lock and the
 * {@code (seat_id, journey_date)} unique constraint are both actually exercised. A mocked test
 * cannot prove this: the whole point is that several transactions race for the same row.
 */
@SpringBootTest
@ActiveProfiles("test")
class SeatHoldConcurrencyIntegrationTest {

    private static final int THREADS = 8;

    /** Booking statuses that hold inventory, so they count towards live seat occupancy. */
    private static final List<BookingStatus> LIVE_BOOKING_STATUSES = List.of(
            BookingStatus.PAYMENT_PENDING, BookingStatus.PAYMENT_FAILED,
            BookingStatus.CONFIRMED, BookingStatus.RAC, BookingStatus.WAITLISTED);

    private static final AtomicInteger COUNTER = new AtomicInteger();

    @Autowired
    private BookingService bookingService;
    @Autowired
    private BookingRepository bookingRepository;
    @Autowired
    private BookingPassengerRepository bookingPassengerRepository;
    @Autowired
    private SeatReservationRepository seatReservationRepository;
    @Autowired
    private CancellationRepository cancellationRepository;
    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private PaymentAttemptRepository paymentAttemptRepository;
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

    private Train rajdhani;
    private Long ndls;
    private Long pnbe;
    private LocalDate journeyDate;

    @BeforeEach
    void setUp() {
        transactionTemplate.executeWithoutResult(status -> {
            cancellationRepository.deleteAll();
            paymentAttemptRepository.deleteAll();
            paymentRepository.deleteAll();
            seatReservationRepository.deleteAll();
            bookingRepository.deleteAll();
            coachRepository.findAll().stream()
                    .filter(c -> c.getCoachCode() != null && c.getCoachCode().startsWith("ZC"))
                    .collect(Collectors.toList())
                    .forEach(coach -> {
                        seatRepository.deleteByCoachId(coach.getId());
                        coachRepository.delete(coach);
                    });
            seatRepository.flush();
            coachRepository.flush();
        });

        rajdhani = trainRepository.findByTrainNumber("12301").orElseThrow();
        ndls = stationRepository.findByCode("NDLS").orElseThrow().getId();
        pnbe = stationRepository.findByCode("PNBE").orElseThrow().getId();
        journeyDate = LocalDate.now().plusDays(1);
    }

    @Test
    @DisplayName("N threads racing for a single seat: exactly one is confirmed, the rest waitlist")
    void concurrentBookingsNeverDoubleAllocateASeat() throws Exception {
        // One seat, many threads. The engine waitlists whoever loses rather than failing them, so the
        // invariant to prove is that only ONE passenger ever ends up holding that seat.
        Coach coach = newTestCoach(1, 0);

        List<Long> users = IntStream.range(0, THREADS).mapToObj(i -> newUser()).toList();
        List<Throwable> results = inParallel(users, uid -> {
            try {
                bookingService.createBooking(uid, request(coach, 1), null);
                return null;
            } catch (RuntimeException ex) {
                return ex;
            }
        });

        // Nobody should be rejected outright: the coach is tiny but the waitlist absorbs the rest.
        assertThat(losers(results)).isEmpty();

        assertThat(countPassengers(coach, PassengerStatus.CONFIRMED))
                .as("exactly one passenger may hold the single seat")
                .isEqualTo(1);
        assertThat(countPassengers(coach, PassengerStatus.WAITLISTED)).isEqualTo(THREADS - 1);
        assertThat(occupiedSeats(coach)).hasSize(1);
    }

    @Test
    @DisplayName("concurrent bookings get distinct seats, up to the number of seats available")
    void concurrentBookingsGetDistinctSeats() throws Exception {
        // Four seats, eight threads: four passengers confirmed on four distinct seats, four waitlisted.
        Coach coach = newTestCoach(4, 0);

        List<Long> users = IntStream.range(0, THREADS).mapToObj(i -> newUser()).toList();
        List<Throwable> results = inParallel(users, uid -> {
            try {
                bookingService.createBooking(uid, request(coach, 1), null);
                return null;
            } catch (RuntimeException ex) {
                return ex;
            }
        });

        assertThat(losers(results)).isEmpty();
        assertThat(countPassengers(coach, PassengerStatus.CONFIRMED)).isEqualTo(4);
        assertThat(countPassengers(coach, PassengerStatus.WAITLISTED)).isEqualTo(THREADS - 4);

        // The unique constraint is the real guarantee: four reservations, four distinct seats.
        assertThat(occupiedSeats(coach)).hasSize(4).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("concurrent bookings on different coaches all succeed")
    void concurrentBookingsOnDifferentCoachesAllSucceed() throws Exception {
        Coach coachA = newTestCoach(2, 0);
        Coach coachB = newTestCoach(2, 0);

        List<Long> users = IntStream.range(0, THREADS).mapToObj(i -> newUser()).toList();
        List<Throwable> results = inParallel(users, uid -> {
            Coach target = users.indexOf(uid) % 2 == 0 ? coachA : coachB;
            try {
                bookingService.createBooking(uid, request(target, 1), null);
                return null;
            } catch (RuntimeException ex) {
                return ex;
            }
        });

        assertThat(losers(results)).isEmpty();
        assertThat(countPassengers(coachA, PassengerStatus.CONFIRMED)).isEqualTo(2);
        assertThat(countPassengers(coachB, PassengerStatus.CONFIRMED)).isEqualTo(2);
        assertThat(occupiedSeats(coachA)).hasSize(2);
        assertThat(occupiedSeats(coachB)).hasSize(2);
    }

    // ------------------------------------------------------------------ helpers

    private List<Long> winners(List<Throwable> results) {
        return IntStream.range(0, results.size())
                .filter(i -> results.get(i) == null)
                .mapToObj(i -> (long) i)
                .toList();
    }

    private List<Throwable> losers(List<Throwable> results) {
        return results.stream().filter(Objects::nonNull).toList();
    }

    private long countPassengers(Coach coach, PassengerStatus status) {
        return bookingPassengerRepository
                .countByBookingCoachIdAndBookingJourneyDateAndPassengerStatusInAndBookingStatusIn(
                        coach.getId(), journeyDate, List.of(status), LIVE_BOOKING_STATUSES);
    }

    /**
     * Runs every task on its own thread at the same time and returns whatever each produced.
     * The latch is what makes this a race rather than a sequence.
     */
    private List<Throwable> inParallel(List<Long> users, Task task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(users.size());
        try {
            CountDownLatch ready = new CountDownLatch(users.size());
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Throwable>> futures = new ArrayList<>();
            for (Long uid : users) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return task.run(uid);
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<Throwable> results = new ArrayList<>();
            for (Future<Throwable> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    @FunctionalInterface
    private interface Task {
        Throwable run(Long userId) throws Exception;
    }

    private List<Long> occupiedSeats(Coach coach) {
        return seatReservationRepository.findOccupiedSeatIdsForCoach(
                        coach.getId(), journeyDate, java.time.LocalDateTime.now())
                .stream()
                .distinct()
                .toList();
    }

    private Coach newTestCoach(int seats, int racCapacity) {
        Coach coach = coachRepository.save(Coach.builder()
                .train(rajdhani)
                .coachCode("ZC" + COUNTER.incrementAndGet())
                .seatClass(SeatClass.THIRD_AC)
                .totalSeats(seats)
                .baseFare(new BigDecimal("950"))
                .racCapacity(racCapacity)
                .active(true)
                .build());
        for (int i = 1; i <= seats; i++) {
            seatRepository.save(Seat.builder()
                    .coach(coach).seatNumber(i)
                    .berthType(i % 2 == 0 ? "WINDOW" : "AISLE").active(true).build());
        }
        return coach;
    }

    private CreateBookingRequest request(Coach coach, int passengers) {
        CreateBookingRequest request = new CreateBookingRequest();
        request.setTrainId(rajdhani.getId());
        request.setCoachId(coach.getId());
        request.setSourceStationId(ndls);
        request.setDestinationStationId(pnbe);
        request.setJourneyDate(journeyDate);
        request.setPassengers(IntStream.range(0, passengers)
                .mapToObj(i -> {
                    PassengerDto dto = new PassengerDto();
                    dto.setFullName("Racer " + (char) ('A' + i));
                    dto.setAge(30);
                    dto.setGender("MALE");
                    dto.setBerthPreference("NO_PREFERENCE");
                    return dto;
                })
                .toList());
        return request;
    }

    private Long newUser() {
        int n = COUNTER.incrementAndGet();
        return userRepository.save(User.builder()
                .fullName("Racer " + n)
                .email("racer" + n + "@example.com")
                .phone("91" + String.format("%08d", n))
                .password(passwordEncoder.encode("Password@123"))
                .role(RoleName.PASSENGER)
                .active(true)
                .build())
                .getId();
    }
}

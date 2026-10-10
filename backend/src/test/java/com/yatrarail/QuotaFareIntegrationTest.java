package com.yatrarail;

import com.yatrarail.dto.booking.*;
import com.yatrarail.dto.fare.FareBreakdownDto;
import com.yatrarail.dto.fare.FarePassengerLineDto;
import com.yatrarail.dto.fare.FarePreviewRequest;
import com.yatrarail.dto.quota.QuotaAvailabilityDto;
import com.yatrarail.dto.quota.QuotaConfigRequest;
import com.yatrarail.entity.*;
import com.yatrarail.exception.BadRequestException;
import com.yatrarail.exception.ConflictException;
import com.yatrarail.exception.ForbiddenException;
import com.yatrarail.repository.*;
import com.yatrarail.service.BookingService;
import com.yatrarail.service.QuotaAdminService;
import com.yatrarail.service.QuotaService;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import jakarta.validation.ConstraintViolation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 4: booking quotas and the itemised fare breakdown.
 * <p>
 * The properties under test are the ones that make a quota system trustworthy rather than merely
 * present:
 * <ul>
 *   <li><b>Eligibility is server-side.</b> Age, gender, a declared disability and the caller's own
 *       role are all checked by the backend, so a crafted request cannot buy a concessionary ticket.</li>
 *   <li><b>A quota cannot be oversold.</b> Its capacity is a hard ceiling, enforced under a
 *       class-wide lock, and it is derived from the class's real seat count so the reserved slices
 *       can never exceed what the train owns.</li>
 *   <li><b>Nothing is silent.</b> When a reserved quota is full the passenger is moved to general
 *       at the full fare <i>with a reason</i>, and the fare breakdown says which quota each
 *       passenger actually got.</li>
 *   <li><b>The arithmetic adds up.</b> The displayed lines always sum to the displayed grand total,
 *       and the quote the passenger reviews is the amount the booking charges.</li>
 *   <li><b>An admin cannot break the past.</b> Shrinking a quota below what is already sold is
 *       refused rather than silently invalidating a paid ticket.</li>
 * </ul>
 * Like the Phase 2/3 suites this is deliberately not wrapped in a test-wide transaction, so locking
 * and flush behaviour are exercised the way they are in production. State is reset in
 * {@link #setUp()}.
 */
@SpringBootTest
@ActiveProfiles("test")
class QuotaFareIntegrationTest {

    private static final AtomicInteger COUNTER = new AtomicInteger();
    private static final String TEST_COACH_PREFIX = "ZU";

    @Autowired
    private BookingService bookingService;
    @Autowired
    private QuotaAdminService quotaAdminService;
    @Autowired
    private QuotaService quotaService;
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
    private QuotaConfigRepository quotaConfigRepository;
    @Autowired
    private FareRuleRepository fareRuleRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private TransactionTemplate transactionTemplate;

    /** The seeded third-AC tariff, restored before every test so a tariff edit cannot leak. */
    private static final String SEEDED_FARE_PER_KM = "2.00";
    private static final String SEEDED_MINIMUM_FARE = "500";
    private static final String SEEDED_PLATFORM_FEE = "30.00";
    private static final String SEEDED_GST_PERCENT = "5.00";

    private Long userId;
    private Long staffId;
    private Long adminId;
    private Train rajdhani;
    private Coach thirdAcCoach;
    private Long ndls;
    private Long lko;
    private LocalDate journeyDate;

    @BeforeEach
    void setUp() {
        resetState();

        int n = COUNTER.incrementAndGet();
        userId = newUser("quota" + n + "@example.com", "Quota Tester", RoleName.PASSENGER);
        staffId = newUser("qstaff" + n + "@example.com", "Quota Staff", RoleName.STAFF);
        adminId = newUser("qadmin" + n + "@example.com", "Quota Admin", RoleName.ADMIN);

        rajdhani = trainRepository.findByTrainNumber("12301").orElseThrow();
        thirdAcCoach = coachRepository.findByTrainIdAndSeatClass(rajdhani.getId(), SeatClass.THIRD_AC)
                .orElseThrow();
        // A test may have parked the seeded coaches to isolate a small class; bring them all back.
        reactivateSeededThirdAcCoaches();
        ndls = stationId("NDLS");
        lko = stationId("LKO");
        journeyDate = LocalDate.now().plusDays(1);

        // Every test starts from "no quotas configured", so the seeded defaults cannot leak in.
        zeroAllQuotas();
        restoreSeededFareRule();
    }

    private void resetState() {
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

    private void zeroAllQuotas() {
        quotaConfigRepository.findByTrainIdOrderBySeatClassAsc(rajdhani.getId()).forEach(config -> {
            config.setTatkalSeats(0);
            config.setSeniorSeats(0);
            config.setLadiesSeats(0);
            config.setDivyangSeats(0);
            config.setStaffSeats(0);
            config.setTatkalWindowDays(1);
            config.setActive(true);
            quotaConfigRepository.save(config);
        });
        quotaConfigRepository.flush();
    }

    // ==================================================================
    // Eligibility
    // ==================================================================

    @Nested
    @DisplayName("quota eligibility is enforced by the backend")
    class Eligibility {

        @Test
        @DisplayName("a passenger under 60 cannot claim the senior citizen quota")
        void seniorCitizenMustActuallyBeSenior() {
            configure(2, 0, 0, 0, 0);

            assertThatThrownBy(() -> create(userId, request(passenger("Young Person", 34, "MALE", QuotaType.SENIOR))))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("not eligible for the Senior Citizen quota");
        }

        @Test
        @DisplayName("a passenger aged exactly 60 qualifies for the senior citizen quota")
        void seniorCitizenAtTheBoundaryIsAccepted() {
            configure(2, 0, 0, 0, 0);

            BookingResponseDto dto = create(userId, request(passenger("Bhawan Devi", 60, "FEMALE", QuotaType.SENIOR)));

            assertThat(dto.getPassengers().get(0).getQuota()).isEqualTo("SENIOR");
            assertThat(dto.getPassengers().get(0).getPassengerStatus()).isEqualTo("CONFIRMED");
        }

        @Test
        @DisplayName("a missing age cannot be used to claim the senior citizen quota")
        void seniorCitizenNeedsAnAge() {
            configure(2, 0, 0, 0, 0);
            PassengerDto noAge = passenger("No Age", null, "MALE", QuotaType.SENIOR);

            assertThatThrownBy(() -> create(userId, request(noAge)))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("age is required");
        }

        @Test
        @DisplayName("a male passenger cannot claim the ladies quota")
        void ladiesQuotaIsFemaleOnly() {
            configure(0, 2, 0, 0, 0);

            assertThatThrownBy(() -> create(userId, request(passenger("Ramesh", 40, "MALE", QuotaType.LADIES))))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("not eligible for the Ladies quota");
        }

        @Test
        @DisplayName("a female passenger can claim the ladies quota")
        void ladiesQuotaAcceptsFemale() {
            configure(0, 2, 0, 0, 0);

            BookingResponseDto dto = create(userId, request(passenger("Sunita", 28, "FEMALE", QuotaType.LADIES)));

            assertThat(dto.getPassengers().get(0).getQuota()).isEqualTo("LADIES");
        }

        @Test
        @DisplayName("the divyang quota needs a declared disability certificate")
        void divyangNeedsTheCertificate() {
            configure(0, 0, 2, 0, 0);
            PassengerDto undeclared = passenger("Vikram", 35, "MALE", QuotaType.DIVYANG);

            assertThatThrownBy(() -> create(userId, request(undeclared)))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("disability certificate must be declared");
        }

        @Test
        @DisplayName("a declared certificate makes the divyang quota usable")
        void divyangWithCertificateIsAccepted() {
            configure(0, 0, 2, 0, 0);
            PassengerDto declared = passenger("Vikram", 35, "MALE", QuotaType.DIVYANG);
            declared.setDisabilityCertified(true);

            assertThat(create(userId, request(declared)).getPassengers().get(0).getQuota())
                    .isEqualTo("DIVYANG");
        }

        @Test
        @DisplayName("the staff quota is refused to an ordinary passenger account")
        void staffQuotaNeedsAStaffAccount() {
            configure(0, 0, 0, 2, 0);
            PassengerDto p = passenger("Passenger", 30, "MALE", QuotaType.STAFF);

            assertThatThrownBy(() -> create(userId, request(p)))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessageContaining("verified staff account");
        }

        @Test
        @DisplayName("the staff quota is granted to a real staff account")
        void staffQuotaWorksForStaff() {
            configure(0, 0, 0, 2, 0);

            BookingResponseDto dto = create(staffId, request(passenger("Staff Rider", 30, "MALE", QuotaType.STAFF)));

            assertThat(dto.getPassengers().get(0).getQuota()).isEqualTo("STAFF");
        }

        @Test
        @DisplayName("a forged quota string is rejected instead of silently treated as general")
        void unknownQuotaIsRejected() {
            configure(2, 0, 0, 0, 0);
            PassengerDto forged = passenger("Forger", 30, "MALE", null);
            // A crafted payload bypassing the DTO's @Pattern: the service must still refuse it.
            forged.setQuota("SUPER_VIP");

            assertThatThrownBy(() -> create(userId, request(forged)))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("Unknown booking quota: SUPER_VIP");
        }
    }

    // ==================================================================
    // Capacity
    // ==================================================================

    @Nested
    @DisplayName("a quota cannot be oversold")
    class Capacity {

        @Test
        @DisplayName("the general quota is whatever the class has left after the reserved slices")
        void generalQuotaIsDerivedFromTheClassCapacity() {
            configure(2, 1, 1, 1, 1);

            QuotaService.QuotaSnapshot snapshot = quotaService.snapshot(thirdAcCoach, journeyDate);
            int total = thirdAcCapacity();

            // The journey is tomorrow and the window is 1 day, so the Tatkal slice is live.
            assertThat(snapshot.isTatkalOpen()).isTrue();
            assertThat(snapshot.capacityOf(QuotaType.TATKAL)).isEqualTo(1);
            assertThat(snapshot.capacityOf(QuotaType.SENIOR)).isEqualTo(2);
            assertThat(snapshot.capacityOf(QuotaType.LADIES)).isEqualTo(1);
            assertThat(snapshot.capacityOf(QuotaType.DIVYANG)).isEqualTo(1);
            assertThat(snapshot.capacityOf(QuotaType.STAFF)).isEqualTo(1);
            assertThat(snapshot.capacityOf(QuotaType.GENERAL)).isEqualTo(total - 6);
            assertThat(total).isEqualTo(snapshot.getTotalCapacity());
        }

        @Test
        @DisplayName("the senior quota hands out exactly its configured number of seats")
        void seniorQuotaIsACeiling() {
            configure(2, 1, 1, 1, 1);

            fillSenior(2);

            QuotaService.QuotaSnapshot snapshot = quotaService.snapshot(thirdAcCoach, journeyDate);
            assertThat(snapshot.capacityOf(QuotaType.SENIOR)).isEqualTo(2);
            assertThat(snapshot.usedOf(QuotaType.SENIOR)).isEqualTo(2);
            assertThat(snapshot.remainingOf(QuotaType.SENIOR)).isZero();
        }

        @Test
        @DisplayName("a senior passenger beyond the quota's capacity falls back to general at full fare")
        void exhaustedSeniorQuotaFallsBackToGeneralWithAnExplanation() {
            configure(2, 1, 1, 1, 1);
            fillSenior(2);

            BookingResponseDto overflow = createAndPay(userId, request(
                    passenger("Bhawan Devi", 62, "FEMALE", QuotaType.SENIOR)));

            PassengerResultDto p = overflow.getPassengers().get(0);
            assertThat(p.getQuota()).isEqualTo("GENERAL");
            assertThat(p.getQuotaDiscountPercent()).isZero();
            assertThat(p.getQuotaNote()).contains("Senior Citizen").contains("full");
            assertThat(p.getPassengerStatus()).isEqualTo("CONFIRMED");
        }

        @Test
        @DisplayName("a general passenger is queued once the whole class is reserved away, even with free seats")
        void generalPassengerIsQueuedWhenTheWholeClassIsReserved() {
            // Reserve the entire class for senior citizens. Plenty of physical seats remain, but
            // every one of them belongs to the senior slice, so a general passenger must be queued
            // rather than handed one of somebody else's seats.
            int total = thirdAcCapacity();
            configure(total, 0, 0, 0, 0);
            assertThat(quotaService.snapshot(thirdAcCoach, journeyDate).capacityOf(QuotaType.GENERAL))
                    .isZero();

            BookingResponseDto general = createAndPay(scratchUser(), request(generalPassenger()));
            assertThat(general.getPassengers().get(0).getQuota()).isEqualTo("GENERAL");
            assertThat(general.getPassengers().get(0).getPassengerStatus())
                    .isIn("RAC", "WAITLISTED");

            // The senior slice, on the other hand, is fully open.
            BookingResponseDto senior = createAndPay(scratchUser(), request(senior()));
            assertThat(senior.getPassengers().get(0).getPassengerStatus()).isEqualTo("CONFIRMED");
            assertThat(senior.getPassengers().get(0).getQuota()).isEqualTo("SENIOR");
        }

        @Test
        @DisplayName("a quota cannot be configured with more seats than the class physically owns")
        void overAllocationIsRejected() {
            int total = (int) coachRepository.sumTotalSeatsByTrainAndClass(rajdhani.getId(), SeatClass.THIRD_AC);

            QuotaConfigRequest config = quotaRequest(total + 1, 0, 0, 0, 0);

            assertThatThrownBy(() -> quotaAdminService.upsertConfig(config))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("Reserved quotas total")
                    .hasMessageContaining("general quota would go negative");
        }

        @Test
        @DisplayName("Tatkal seats with a zero-day window are rejected as a contradiction")
        void tatkalWithoutAWindowIsRejected() {
            QuotaConfigRequest config = quotaRequest(0, 0, 0, 0, 0);
            config.setTatkalSeats(3);
            config.setTatkalWindowDays(0);

            assertThatThrownBy(() -> quotaAdminService.upsertConfig(config))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("departure window is 0 days");
        }
    }

    // ==================================================================
    // Tatkal window
    // ==================================================================

    @Nested
    @DisplayName("the Tatkal-sim quota only exists inside its departure window")
    class TatkalWindow {

        @Test
        @DisplayName("outside the window the Tatkal slice is not carved out, so those seats stay general")
        void outsideTheWindowTatkalSeatsBelongToGeneral() {
            // A 1-day window: tomorrow is inside it, a month away is not.
            configureWithWindow(2, 1, 1);
            LocalDate farFuture = LocalDate.now().plusDays(30);

            QuotaService.QuotaSnapshot snapshot = quotaService.snapshot(thirdAcCoach, farFuture);
            int total = thirdAcCapacity();

            assertThat(snapshot.isTatkalOpen()).isFalse();
            assertThat(snapshot.capacityOf(QuotaType.TATKAL)).isZero();
            assertThat(snapshot.capacityOf(QuotaType.GENERAL)).isEqualTo(total - 1);
        }

        @Test
        @DisplayName("inside the window the Tatkal slice is carved out of the general quota")
        void insideTheWindowTatkalSeatsAreReserved() {
            configureWithWindow(2, 1, 1);
            LocalDate tomorrow = LocalDate.now().plusDays(1);

            QuotaService.QuotaSnapshot snapshot = quotaService.snapshot(thirdAcCoach, tomorrow);
            int total = thirdAcCapacity();

            assertThat(snapshot.isTatkalOpen()).isTrue();
            assertThat(snapshot.capacityOf(QuotaType.TATKAL)).isEqualTo(2);
            assertThat(snapshot.capacityOf(QuotaType.GENERAL)).isEqualTo(total - 3);
        }

        @Test
        @DisplayName("a Tatkal request outside the window is rejected outright, not silently ignored")
        void tatkalOutsideTheWindowIsRejected() {
            configureWithWindow(2, 1, 1);

            // The journey is a month away, so the 1-day window is shut.
            CreateBookingRequest request = request(passenger("Late Booker", 30, "MALE", QuotaType.TATKAL));
            request.setJourneyDate(LocalDate.now().plusDays(30));

            assertThatThrownBy(() -> create(userId, request))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("Tatkal");
        }

        @Test
        @DisplayName("a Tatkal request inside the window is granted the quota")
        void tatkalInsideTheWindowIsGranted() {
            configureWithWindow(2, 1, 1);

            BookingResponseDto dto = createAndPay(userId,
                    request(passenger("Late Booker", 30, "MALE", QuotaType.TATKAL)));

            assertThat(dto.getPassengers().get(0).getQuota()).isEqualTo("TATKAL");
        }
    }

    // ==================================================================
    // The fare breakdown
    // ==================================================================

    @Nested
    @DisplayName("the fare breakdown is complete and internally consistent")
    class FareBreakdown {

        @Test
        @DisplayName("the displayed lines always add up to the grand total")
        void linesAddUpToTheTotal() {
            BookingResponseDto dto = createAndPay(userId, request(threePassengers()));

            FareBreakdownDto b = dto.getFareBreakdown();
            BigDecimal lineSum = b.getPassengers().stream()
                    .map(FarePassengerLineDto::getPayableFare)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal discountSum = b.getPassengers().stream()
                    .map(FarePassengerLineDto::getDiscountAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            assertThat(lineSum).isEqualByComparingTo(b.getPassengerFareTotal());
            assertThat(discountSum).isEqualByComparingTo(b.getDiscountTotal());
            assertThat(b.getGrossFareTotal().subtract(b.getDiscountTotal()))
                    .isEqualByComparingTo(b.getPassengerFareTotal());
            assertThat(b.getPassengerFareTotal().add(b.getPlatformFee()).add(b.getGstAmount()))
                    .isEqualByComparingTo(b.getTotalPayable());
            assertThat(b.getTotalPayable()).isEqualByComparingTo(dto.getTotalFare());
        }

        @Test
        @DisplayName("the breakdown names which of the three candidates set the fare")
        void breakdownExplainsThePricingBasis() {
            FareBreakdownDto b = createAndPay(userId, request(threePassengers())).getFareBreakdown();

            assertThat(b.getFareBasis()).isIn("COACH_BASE", "DISTANCE", "MINIMUM");
            assertThat(b.getCoachBaseFare()).isNotNull();
            assertThat(b.getFarePerKm()).isNotNull();
            assertThat(b.getDistanceFare()).isEqualByComparingTo(
                    b.getFarePerKm().multiply(BigDecimal.valueOf(b.getDistanceKm())));
            assertThat(b.getMinimumFare()).isNotNull();
            assertThat(b.getGrossFarePerPassenger()).isEqualByComparingTo(
                    b.getCoachBaseFare().max(b.getDistanceFare()).max(b.getMinimumFare()));
            assertThat(b.getNotes()).isNotEmpty();
        }

        @Test
        @DisplayName("GST is charged on the passenger subtotal, never on the platform fee")
        void gstIsNotAppliedToThePlatformFee() {
            FareBreakdownDto b = createAndPay(userId, request(threePassengers())).getFareBreakdown();

            BigDecimal expectedGst = b.getPassengerFareTotal()
                    .multiply(b.getGstPercent())
                    .divide(BigDecimal.valueOf(100), 2, java.math.RoundingMode.HALF_UP);

            assertThat(b.getGstAmount()).isEqualByComparingTo(expectedGst);
            assertThat(b.getPlatformFee()).isGreaterThan(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("a senior concession is applied and shown on that passenger's line only")
        void concessionIsAppliedPerPassenger() {
            configure(2, 0, 0, 0, 0);

            List<PassengerDto> mixed = List.of(
                    passenger("Bhawan Devi", 65, "FEMALE", QuotaType.SENIOR),
                    passenger("Ram Babu", 30, "MALE", QuotaType.GENERAL));
            BookingResponseDto dto = createAndPay(userId, request(mixed));

            FarePassengerLineDto senior = dto.getFareBreakdown().getPassengers().get(0);
            FarePassengerLineDto general = dto.getFareBreakdown().getPassengers().get(1);

            assertThat(senior.getQuota()).isEqualTo("SENIOR");
            assertThat(senior.getDiscountPercent()).isEqualTo(40);
            assertThat(general.getQuota()).isEqualTo("GENERAL");
            assertThat(general.getDiscountPercent()).isZero();

            BigDecimal gross = dto.getFareBreakdown().getGrossFarePerPassenger();
            assertThat(senior.getPayableFare()).isLessThan(gross);
            assertThat(general.getPayableFare()).isEqualByComparingTo(gross);
        }

        @Test
        @DisplayName("a stored booking keeps the price it was created with, whatever the tariff says now")
        void storedBreakdownSurvivesATariffChange() {
            BookingResponseDto created = createAndPay(userId, request(threePassengers()));
            BigDecimal chargedAtBooking = created.getTotalFare();
            String basis = created.getFareBreakdown().getFareBasis();

            // Change the tariff after the fact; the ticket must not move.
            BigDecimal platformFee = created.getFareBreakdown().getPlatformFee();
            BigDecimal gstPercent = created.getFareBreakdown().getGstPercent();
            fareRuleOverride("999.00", "9999.00", platformFee, gstPercent);

            BookingResponseDto reread = bookingService.getBookingDetails(userId, created.getId());
            assertThat(reread.getTotalFare()).isEqualByComparingTo(chargedAtBooking);
            assertThat(reread.getFareBreakdown().getFareBasis()).isEqualTo(basis);
            assertThat(reread.getFareBreakdown().getTotalPayable()).isEqualByComparingTo(chargedAtBooking);
        }

        @Test
        @DisplayName("the quote the passenger reviews is exactly what the booking charges")
        void quoteMatchesTheCreatedBooking() {
            List<PassengerDto> passengers = threePassengers();
            BookingQuoteDto quoted = bookingService.quote(quoteRequest(passengers), RoleName.PASSENGER);
            BookingResponseDto created = createAndPay(userId, request(passengers));

            assertThat(created.getTotalFare()).isEqualByComparingTo(quoted.getTotalFare());
            assertThat(created.getFareBreakdown().getPassengers()).hasSameSizeAs(
                    quoted.getFareBreakdown().getPassengers());
            assertThat(created.getFareBreakdown().getPassengers().get(0).getQuota())
                    .isEqualTo(quoted.getFareBreakdown().getPassengers().get(0).getQuota());
        }

        @Test
        @DisplayName("a pre-Phase-4 booking still renders a sane, self-consistent breakdown")
        void legacyBookingWithoutASnapshotStillRenders() {
            BookingResponseDto created = createAndPay(userId, request(threePassengers()));
            Long bookingId = created.getId();
            BigDecimal charged = created.getTotalFare();
            BigDecimal perPassenger = created.getFareBreakdown().getGrossFarePerPassenger();

            // Simulate a row written before Phase 4: no fare_basis, and a passenger_fare_total that
            // the migration backfilled as fare_per_passenger * passenger count. The response must be
            // rebuilt from the legacy model, not read as a Phase 4 snapshot with zero gross fare.
            transactionTemplate.executeWithoutResult(status -> {
                Booking booking = bookingRepository.findById(bookingId).orElseThrow();
                booking.setFareBasis(null);
                booking.setFareBase(null);
                booking.setDistanceFare(null);
                booking.setFareMinimum(null);
                booking.setPassengerFareTotal(perPassenger.multiply(BigDecimal.valueOf(3)));
                booking.setDiscountTotal(BigDecimal.ZERO);
                booking.setPlatformFee(BigDecimal.ZERO);
                booking.setGstPercent(BigDecimal.ZERO);
                booking.setGstAmount(BigDecimal.ZERO);
                bookingRepository.saveAndFlush(booking);
            });

            FareBreakdownDto legacy = bookingService.getBookingDetails(userId, bookingId).getFareBreakdown();

            assertThat(legacy.getGrossFarePerPassenger()).isEqualByComparingTo(perPassenger);
            assertThat(legacy.getGrossFareTotal()).isEqualByComparingTo(perPassenger.multiply(BigDecimal.valueOf(3)));
            assertThat(legacy.getPassengerFareTotal()).isEqualByComparingTo(perPassenger.multiply(BigDecimal.valueOf(3)));
            assertThat(legacy.getDiscountTotal()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(legacy.getPlatformFee()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(legacy.getGstAmount()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(legacy.getTotalPayable()).isEqualByComparingTo(charged);
            assertThat(legacy.getPassengers()).allSatisfy(line -> {
                assertThat(line.getQuota()).isEqualTo("GENERAL");
                assertThat(line.getDiscountPercent()).isZero();
                assertThat(line.getGrossFare()).isEqualByComparingTo(perPassenger);
            });
            assertThat(legacy.getNotes()).anyMatch(n -> n.contains("Legacy fare"));
        }

        @Test
        @DisplayName("the refund excludes the platform fee from the tiered percentage but returns it in full")
        void refundKeepsThePlatformFee() {
            BookingResponseDto dto = createAndPay(userId, request(threePassengers()));
            Long bookingId = dto.getId();

            // Cancelling 10+ days out is the 90% tier; tomorrow's journey is the 25% tier. Book far
            // out so the top tier applies and the arithmetic is easy to check.
            assertThat(dto.getFareBreakdown().getPlatformFee()).isPositive();

            CancelBookingRequest request = new CancelBookingRequest();
            request.setReason("Phase 4 refund test");
            BookingResponseDto cancelled = bookingService.cancelBooking(userId, bookingId, request);

            assertThat(cancelled.getStatus()).isEqualTo("CANCELLED");
            // The fee is returned even though it is not a fare, and the total cannot exceed what
            // was charged.
            assertThat(cancellationRepository.findAll().stream()
                    .filter(c -> c.getBooking().getId().equals(bookingId))
                    .findFirst().orElseThrow().getRefundAmount())
                    .isLessThanOrEqualTo(dto.getTotalFare());
        }
    }

    // ==================================================================
    // Lifecycle
    // ==================================================================

    @Nested
    @DisplayName("quota is released and re-checked across the booking lifecycle")
    class Lifecycle {

        @Test
        @DisplayName("cancelling a booking releases its quota for the next passenger")
        void cancellationReleasesTheQuota() {
            configure(1, 0, 0, 0, 0);

            Long firstOwner = scratchUser();
            BookingResponseDto first = createAndPay(firstOwner, request(senior()));
            assertThat(quotaService.snapshot(thirdAcCoach, journeyDate).usedOf(QuotaType.SENIOR)).isEqualTo(1);

            CancelBookingRequest cancel = new CancelBookingRequest();
            cancel.setReason("Freeing the quota");
            bookingService.cancelBooking(firstOwner, first.getId(), cancel);

            assertThat(quotaService.snapshot(thirdAcCoach, journeyDate).usedOf(QuotaType.SENIOR)).isZero();

            // The freed seat is usable by a new senior passenger, again under the senior quota.
            BookingResponseDto second = createAndPay(scratchUser(), request(senior()));
            assertThat(second.getPassengers().get(0).getQuota()).isEqualTo("SENIOR");
        }

        @Test
        @DisplayName("an unpaid hold already consumes its quota, so it cannot be double-sold")
        void unpaidHoldConsumesTheQuota() {
            configure(1, 0, 0, 0, 0);

            create(scratchUser(), request(senior()));

            assertThat(quotaService.snapshot(thirdAcCoach, journeyDate).usedOf(QuotaType.SENIOR)).isEqualTo(1);
            BookingResponseDto overflow = createAndPay(scratchUser(), request(senior()));
            assertThat(overflow.getPassengers().get(0).getQuota()).isEqualTo("GENERAL");
            assertThat(overflow.getPassengers().get(0).getQuotaNote())
                    .contains("Senior Citizen").contains("full");
        }

        @Test
        @DisplayName("a promotion re-checks the recorded quota and refuses to oversell it")
        void promotionRespectsTheRecordedQuota() {
            // This needs a class small enough to exhaust on purpose, because filling the seeded
            // third-AC class would take dozens of bookings. Swapping in a two-seat coach makes the
            // whole scenario three bookings; setUp() re-activates the seeded coaches for every test.
            Coach tiny = isolateTwoSeatThirdAcClass();
            assertThat(thirdAcCapacity()).isEqualTo(2);
            // One seat for senior citizens, the single remaining seat stays general. No RAC, so an
            // unseatable passenger is waitlisted rather than given a shared berth.
            configure(1, 0, 0, 0, 0);

            // The general passenger takes the last general seat.
            Long generalOwner = scratchUser();
            BookingResponseDto general = createAndPay(generalOwner, request(tiny, generalPassenger()));
            assertThat(general.getPassengers().get(0).getPassengerStatus()).isEqualTo("CONFIRMED");

            // The senior passenger takes the senior seat, exhausting that quota too.
            BookingResponseDto seniorFirst = createAndPay(scratchUser(), request(tiny, senior()));
            assertThat(seniorFirst.getPassengers().get(0).getPassengerStatus()).isEqualTo("CONFIRMED");
            assertThat(quotaService.snapshot(tiny, journeyDate).remainingOf(QuotaType.SENIOR)).isZero();

            // Now both quotas are full and the coach is physically full, so this senior passenger
            // joins the queue still recorded under SENIOR - the quota they claimed and were priced at.
            Long queuedOwner = scratchUser();
            BookingResponseDto seniorQueued = createAndPay(queuedOwner, request(tiny, senior()));
            assertThat(seniorQueued.getPassengers().get(0).getQuota()).isEqualTo("SENIOR");
            assertThat(seniorQueued.getPassengers().get(0).getPassengerStatus())
                    .isIn("RAC", "WAITLISTED");

            // Freeing a general seat triggers a promotion, but the queued passenger's recorded
            // quota (SENIOR) is full, so they must NOT be moved onto general: their fare was
            // already priced as a senior citizen, and handing them a general seat would both
            // oversell the senior quota and undercharge.
            CancelBookingRequest cancel = new CancelBookingRequest();
            cancel.setReason("Releasing a seat");
            bookingService.cancelBooking(generalOwner, general.getId(), cancel);

            BookingResponseDto reread = bookingService.getBookingDetails(queuedOwner, seniorQueued.getId());
            assertThat(reread.getPassengers().get(0).getPassengerStatus())
                    .isIn("RAC", "WAITLISTED");
            assertThat(reread.getPassengers().get(0).getQuota()).isEqualTo("SENIOR");

            // The general seat came back and nobody took it: that is the point. A physically free
            // seat sits unused rather than being handed to a passenger whose recorded quota is full.
            QuotaService.QuotaSnapshot after = quotaService.snapshot(tiny, journeyDate);
            assertThat(after.remainingOf(QuotaType.GENERAL)).isEqualTo(1);
            assertThat(after.remainingOf(QuotaType.SENIOR)).isZero();
            assertThat(after.usedOf(QuotaType.GENERAL)).isZero();
        }
    }

    // ==================================================================
    // Admin surface
    // ==================================================================

    @Nested
    @DisplayName("the admin surface protects existing bookings")
    class AdminSurface {

        @Test
        @DisplayName("shrinking a quota below what is already sold is refused")
        void cannotShrinkAQuotaBelowSoldSeats() {
            configure(2, 0, 0, 0, 0);
            fillSenior(2);

            QuotaConfigRequest shrink = quotaRequest(1, 0, 0, 0, 0);

            assertThatThrownBy(() -> quotaAdminService.upsertConfig(shrink))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("already booked")
                    .hasMessageContaining("Senior Citizen");
        }

        @Test
        @DisplayName("switching quotas off is refused while seats are sold under them")
        void cannotDisableQuotasThatAreInUse() {
            configure(2, 0, 0, 0, 0);
            fillSenior(2);

            QuotaConfigRequest disable = quotaRequest(0, 0, 0, 0, 0);
            disable.setActive(false);

            assertThatThrownBy(() -> quotaAdminService.upsertConfig(disable))
                    .isInstanceOf(ConflictException.class);
        }

        @Test
        @DisplayName("a larger quota is accepted and widens the remaining room")
        void aLargerQuotaIsAccepted() {
            configure(1, 0, 0, 0, 0);
            fillSenior(1);

            quotaAdminService.upsertConfig(quotaRequest(4, 0, 0, 0, 0));

            BookingResponseDto second = createAndPay(userId, request(senior()));
            assertThat(second.getPassengers().get(0).getQuota()).isEqualTo("SENIOR");
        }

        @Test
        @DisplayName("the admin fare preview will not quote a concession the booking would refuse")
        void previewRefusesAnIneligibleConcession() {
            configure(2, 0, 0, 0, 0);

            assertThatThrownBy(() -> quotaAdminService.previewFare(
                    previewRequest(passenger("Young Person", 34, "MALE", QuotaType.SENIOR))))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("not eligible for the Senior Citizen quota");
        }

        @Test
        @DisplayName("the admin fare preview prices an eligible concession without booking anything")
        void previewPricesAnEligibleConcession() {
            configure(2, 0, 0, 0, 0);

            FareBreakdownDto preview = quotaAdminService.previewFare(previewRequest(senior()));

            FarePassengerLineDto line = preview.getPassengers().get(0);
            assertThat(line.getQuota()).isEqualTo("SENIOR");
            assertThat(line.getDiscountPercent()).isEqualTo(40);
            assertThat(line.getPayableFare()).isLessThan(preview.getGrossFarePerPassenger());
            assertThat(preview.getTotalPayable())
                    .isEqualByComparingTo(preview.getPassengerFareTotal()
                            .add(preview.getPlatformFee()).add(preview.getGstAmount()));
            assertThat(preview.getNotes()).anyMatch(n -> n.contains("Preview only"));
            // A preview is read-only: it must not have consumed any inventory.
            assertThat(bookingRepository.findAll()).isEmpty();
            assertThat(seatReservationRepository.findAll()).isEmpty();
        }

        @Test
        @DisplayName("the general quota cannot be discounted")
        void generalQuotaCannotBeDiscounted() {
            com.yatrarail.dto.quota.QuotaFareRequest request =
                    new com.yatrarail.dto.quota.QuotaFareRequest();
            request.setSeatClass(SeatClass.THIRD_AC.name());
            request.setQuota("GENERAL");
            request.setDiscountPercent(50);

            assertThatThrownBy(() -> quotaAdminService.upsertQuotaFare(request))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("cannot be discounted");
        }

        @Test
        @DisplayName("a discount above the 90% ceiling is rejected by bean validation")
        void discountCeilingIsEnforced() {
            com.yatrarail.dto.quota.QuotaFareRequest request =
                    new com.yatrarail.dto.quota.QuotaFareRequest();
            request.setSeatClass(SeatClass.THIRD_AC.name());
            request.setQuota("SENIOR");
            request.setDiscountPercent(95);

            Set<ConstraintViolation<com.yatrarail.dto.quota.QuotaFareRequest>> violations =
                    jakarta.validation.Validation.buildDefaultValidatorFactory()
                            .getValidator().validate(request);

            assertThat(violations).isNotEmpty();
            assertThat(violations).anyMatch(v -> "Discount must be at most 90%".equals(v.getMessage()));
        }

        @Test
        @DisplayName("the availability list reports every quota, including why one is unusable")
        void availabilityExplainsItself() {
            QuotaConfigRequest config = quotaRequest(2, 3, 0, 0, 3);
            quotaAdminService.upsertConfig(config);

            List<QuotaAvailabilityDto> availability = quotaService.availability(thirdAcCoach, journeyDate);

            assertThat(availability).extracting(QuotaAvailabilityDto::getQuota)
                    .containsExactly("GENERAL", "TATKAL", "SENIOR", "LADIES", "DIVYANG", "STAFF");
            assertThat(availability).allSatisfy(q -> assertThat(q.getRemaining()).isNotNegative());

            QuotaAvailabilityDto tatkal = availability.stream()
                    .filter(q -> "TATKAL".equals(q.getQuota())).findFirst().orElseThrow();
            assertThat(tatkal.isOpen()).isTrue();
            assertThat(tatkal.getCapacity()).isEqualTo(3);
            assertThat(tatkal.isReserved()).isTrue();

            QuotaAvailabilityDto divyang = availability.stream()
                    .filter(q -> "DIVYANG".equals(q.getQuota())).findFirst().orElseThrow();
            assertThat(divyang.getCapacity()).isZero();
            assertThat(divyang.getUnavailableReason()).contains("No seats are reserved");

            QuotaAvailabilityDto general = availability.stream()
                    .filter(q -> "GENERAL".equals(q.getQuota())).findFirst().orElseThrow();
            assertThat(general.isReserved()).isFalse();
            assertThat(general.getCapacity()).isEqualTo(thirdAcCapacity() - 8);
        }
    }

    // ==================================================================
    // helpers
    // ==================================================================

    /** Books and pays {@code count} distinct senior passengers, consuming that many senior seats. */
    private void fillSenior(int count) {
        for (int i = 0; i < count; i++) {
            PassengerDto p = passenger("Senior " + i, 62 + i, "MALE", QuotaType.SENIOR);
            p.setFullName("Senior " + i + " " + COUNTER.incrementAndGet());
            // A throwaway account per booking: a passenger may hold only one live booking per
            // train and date, which is a Phase 2 rule these tests must not fight.
            createAndPay(scratchUser(), request(p));
        }
    }

    /**
     * A throwaway passenger account. The duplicate-booking guard is per (user, train, date), so any
     * test that needs more than one live booking must use a different account each time.
     */
    private Long scratchUser() {
        int n = COUNTER.incrementAndGet();
        return newUser("scratch" + n + "-" + System.nanoTime() + "@example.com", "Scratch " + n,
                RoleName.PASSENGER);
    }

    private PassengerDto senior() {
        PassengerDto p = passenger("Bhawan Devi", 64, "FEMALE", QuotaType.SENIOR);
        p.setFullName("Bhawan Devi " + COUNTER.incrementAndGet());
        return p;
    }

    private PassengerDto generalPassenger() {
        PassengerDto p = passenger("Ram Babu", 30, "MALE", QuotaType.GENERAL);
        p.setFullName("Ram Babu " + COUNTER.incrementAndGet());
        return p;
    }

    /** Seats the whole third-AC class owns right now, across every active coach of the train. */
    private int thirdAcCapacity() {
        return (int) coachRepository.sumTotalSeatsByTrainAndClass(rajdhani.getId(), SeatClass.THIRD_AC);
    }

    /**
     * Replaces the seeded third-AC coaches with a single two-seat coach, so a test can exhaust the
     * whole class in three bookings. Quota capacity is a per-(train, class) sum over active coaches,
     * which is exactly why the class has to be made small rather than filling the seeded one.
     * <p>
     * {@link #setUp()} re-activates the seeded coaches before every test and {@link #resetState()}
     * deletes the throwaway coach, so this cannot leak.
     */
    private Coach isolateTwoSeatThirdAcClass() {
        return transactionTemplate.execute(status -> {
            for (Coach seeded : coachRepository.findByTrainId(rajdhani.getId())) {
                if (seeded.getSeatClass() == SeatClass.THIRD_AC && Boolean.TRUE.equals(seeded.getActive())) {
                    seeded.setActive(false);
                    coachRepository.save(seeded);
                }
            }
            Coach tiny = coachRepository.saveAndFlush(Coach.builder()
                    .train(rajdhani)
                    .coachCode("ZUP" + COUNTER.incrementAndGet())
                    .seatClass(SeatClass.THIRD_AC)
                    .totalSeats(2)
                    .baseFare(new BigDecimal("950"))
                    .racCapacity(0)
                    .active(true)
                    .build());
            for (int i = 1; i <= 2; i++) {
                seatRepository.save(Seat.builder()
                        .coach(tiny)
                        .seatNumber(i)
                        .berthType(i == 1 ? "WINDOW" : "AISLE")
                        .active(true)
                        .build());
            }
            seatRepository.flush();
            return tiny;
        });
    }

    /** Undoes {@link #isolateTwoSeatThirdAcClass()}, so later tests see the seeded class again. */
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

    private List<PassengerDto> threePassengers() {
        List<PassengerDto> list = new ArrayList<>();
        IntStream.rangeClosed(1, 3).forEach(i -> {
            PassengerDto p = passenger("Rider " + i, 30, i == 2 ? "FEMALE" : "MALE", QuotaType.GENERAL);
            p.setFullName("Rider " + i + " " + COUNTER.incrementAndGet());
            list.add(p);
        });
        return list;
    }

    private PassengerDto passenger(String name, Integer age, String gender, QuotaType quota) {
        PassengerDto dto = new PassengerDto();
        dto.setFullName(name);
        dto.setAge(age);
        dto.setGender(gender);
        dto.setBerthPreference("NO_PREFERENCE");
        dto.setQuota(quota == null ? null : quota.name());
        return dto;
    }

    private void configure(int senior, int ladies, int divyang, int staff, int unused) {
        quotaAdminService.upsertConfig(quotaRequest(senior, ladies, divyang, staff, unused));
    }

    private void configureWithWindow(int tatkal, int senior, int windowDays) {
        QuotaConfigRequest config = quotaRequest(senior, 0, 0, 0, 0);
        config.setTatkalSeats(tatkal);
        config.setTatkalWindowDays(windowDays);
        quotaAdminService.upsertConfig(config);
    }

    private QuotaConfigRequest quotaRequest(int senior, int ladies, int divyang, int staff, int tatkal) {
        QuotaConfigRequest config = new QuotaConfigRequest();
        config.setTrainId(rajdhani.getId());
        config.setSeatClass(SeatClass.THIRD_AC.name());
        config.setTatkalSeats(tatkal);
        config.setSeniorSeats(senior);
        config.setLadiesSeats(ladies);
        config.setDivyangSeats(divyang);
        config.setStaffSeats(staff);
        config.setTatkalWindowDays(1);
        config.setActive(true);
        return config;
    }

    /** Overrides the third-AC tariff in place so a test can prove the snapshot is immutable. */
    private void fareRuleOverride(String farePerKm, String minimumFare, BigDecimal platformFee,
                                  BigDecimal gstPercent) {
        transactionTemplate.executeWithoutResult(status -> {
            FareRule rule = fareRuleRepository.findBySeatClass(SeatClass.THIRD_AC).orElseThrow();
            rule.setFarePerKm(new BigDecimal(farePerKm));
            rule.setMinimumFare(new BigDecimal(minimumFare));
            rule.setPlatformFee(platformFee);
            rule.setGstPercent(gstPercent);
            fareRuleRepository.saveAndFlush(rule);
        });
    }

    /** The tariff is global, so any test that edits it must not affect the next one. */
    private void restoreSeededFareRule() {
        fareRuleOverride(SEEDED_FARE_PER_KM, SEEDED_MINIMUM_FARE,
                new BigDecimal(SEEDED_PLATFORM_FEE), new BigDecimal(SEEDED_GST_PERCENT));
    }

    private BookingResponseDto create(Long uid, CreateBookingRequest request) {
        return bookingService.createBooking(uid, request, null);
    }

    private BookingResponseDto createAndPay(Long uid, CreateBookingRequest request) {
        BookingResponseDto dto = create(uid, request);
        ProcessPaymentRequest payment = new ProcessPaymentRequest();
        payment.setMethod("UPI");
        payment.setSimulateSuccess(true);
        return bookingService.processPayment(uid, dto.getId(), payment, null);
    }

    private CreateBookingRequest request(PassengerDto... passengers) {
        return request(thirdAcCoach, passengers);
    }

    private CreateBookingRequest request(Coach coach, PassengerDto... passengers) {
        CreateBookingRequest request = new CreateBookingRequest();
        request.setTrainId(rajdhani.getId());
        request.setCoachId(coach.getId());
        request.setSourceStationId(ndls);
        request.setDestinationStationId(lko);
        request.setJourneyDate(journeyDate);
        request.setPassengers(List.of(passengers));
        return request;
    }

    private CreateBookingRequest request(List<PassengerDto> passengers) {
        return request(passengers.toArray(new PassengerDto[0]));
    }

    private BookingQuoteRequest quoteRequest(List<PassengerDto> passengers) {
        BookingQuoteRequest request = new BookingQuoteRequest();
        request.setTrainId(rajdhani.getId());
        request.setCoachId(thirdAcCoach.getId());
        request.setSourceStationId(ndls);
        request.setDestinationStationId(lko);
        request.setJourneyDate(journeyDate);
        request.setPassengers(passengers.size());
        request.setPassengerDetails(passengers);
        return request;
    }

    private FarePreviewRequest previewRequest(PassengerDto... passengers) {
        FarePreviewRequest request = new FarePreviewRequest();
        request.setTrainId(rajdhani.getId());
        request.setCoachId(thirdAcCoach.getId());
        request.setSourceStationId(ndls);
        request.setDestinationStationId(lko);
        request.setJourneyDate(journeyDate);
        request.setPassengers(List.of(passengers));
        return request;
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
}

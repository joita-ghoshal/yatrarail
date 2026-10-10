package com.yatrarail.service;

import com.yatrarail.config.BookingProperties;
import com.yatrarail.dto.booking.*;
import com.yatrarail.dto.common.PageResponse;
import com.yatrarail.dto.fare.FareBreakdownDto;
import com.yatrarail.entity.*;
import com.yatrarail.exception.BadRequestException;
import com.yatrarail.exception.ConflictException;
import com.yatrarail.exception.ResourceNotFoundException;
import com.yatrarail.observability.DomainEventPublisher;
import com.yatrarail.observability.PlatformEvent;
import com.yatrarail.repository.*;
import com.yatrarail.service.FareCalculationService.PassengerFare;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Booking lifecycle: quote → create (holds inventory) → pay (confirms it) → cancel (releases it).
 * <p>
 * Phase 2 rewrote this service so that:
 * <ul>
 *   <li>the booked segment is validated against the train's real route, in forward order, on a day
 *       the train actually runs;</li>
 *   <li>the fare is the distance of the booked segment, not the train's full route;</li>
 *   <li>seats are held (and then locked in the database) instead of being allocated optimistically,
 *       so two concurrent bookings can never take the same seat;</li>
 *   <li>creation and payment are idempotent, so a double-click or a network retry is harmless;</li>
 *   <li>a passenger cannot hold two live bookings on the same train and date.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookingService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] REF_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();

    /** Booking statuses that still hold inventory and therefore block a duplicate booking. */
    private static final List<BookingStatus> LIVE_STATUSES = List.of(
            BookingStatus.PAYMENT_PENDING, BookingStatus.PAYMENT_FAILED,
            BookingStatus.CONFIRMED, BookingStatus.RAC, BookingStatus.WAITLISTED);

    private final BookingRepository bookingRepository;
    private final BookingPassengerRepository bookingPassengerRepository;
    private final TrainRepository trainRepository;
    private final CoachRepository coachRepository;
    private final StationRepository stationRepository;
    private final SeatReservationRepository seatReservationRepository;
    private final RouteStationRepository routeStationRepository;
    private final PaymentRepository paymentRepository;
    private final CancellationRepository cancellationRepository;
    private final UserRepository userRepository;
    private final FareCalculationService fareCalculationService;
    private final NotificationService notificationService;
    private final SeatAllocationService seatAllocationService;
    private final WaitlistQueueService waitlistQueueService;
    private final TrainScheduleValidator scheduleValidator;
    private final BookingProperties bookingProperties;
    private final QuotaService quotaService;
    /**
     * Phase 5: the payment ledger. Every attempt is appended here, including the failures, so a
     * passenger's history and the admin reconciliation report both have something to read.
     */
    private final PaymentLedgerService paymentLedgerService;
    /** Phase 5: the single place refund money is decided, shared with the passenger preview. */
    private final RefundService refundService;
    private final PassengerMapper passengerMapper;
    /**
     * Phase 10: business facts are announced after they have been written, so monitoring (and, later,
     * a real broker) can observe the booking lifecycle without the booking lifecycle knowing about
     * either. Publishing never changes the outcome of the write.
     */
    private final DomainEventPublisher domainEventPublisher;

    // ------------------------------------------------------------------ quote

    /**
     * Prices a booking without creating one.
     * <p>
     * Phase 4: the quote is now the same arithmetic the booking will run, so the passenger reviews
     * the exact number they will be charged. When the caller supplies
     * {@code passengerDetails} each passenger's quota is resolved individually - the same
     * eligibility rules and the same fallback as at creation time - and the itemised breakdown comes
     * back. Without passenger details the quote falls back to anonymous general-quota passengers,
     * so existing simple callers and the public search path are unaffected.
     */
    @Transactional(readOnly = true)
    public BookingQuoteDto quote(BookingQuoteRequest request, RoleName actorRole) {
        JourneyContext context = resolveJourney(request.getTrainId(), request.getCoachId(),
                request.getSourceStationId(), request.getDestinationStationId(), request.getJourneyDate());
        int distanceKm = context.segment().distanceKm();
        boolean allowRac = request.getAllowRac() == null || request.getAllowRac();

        List<PassengerDto> details = request.getPassengerDetails();
        int passengers = details != null && !details.isEmpty()
                ? details.size()
                : (request.getPassengers() == null ? 1 : request.getPassengers());

        BigDecimal perPassenger =
                fareCalculationService.calculateFarePerPassenger(context.coach(), distanceKm);

        // Quotas and concessions are resolved exactly as they will be at creation, so the quote
        // cannot quote a concession the backend would then refuse.
        QuotaService.QuotaSnapshot quotas = quotaService.snapshot(context.coach(), request.getJourneyDate());
        List<com.yatrarail.dto.quota.QuotaAvailabilityDto> quotaAvailability =
                quotaService.availability(context.coach(), request.getJourneyDate());
        List<PassengerFare> passengerFares =
                resolvePassengerFares(details, context.coach().getSeatClass(), actorRole, quotas, passengers);

        FareCalculationService.Tariff tariff = fareCalculationService.resolveTariff(context.coach().getSeatClass());
        FareBreakdownDto breakdown = fareCalculationService.buildBreakdown(
                context.coach(), distanceKm, distanceKm, passengerFares, tariff);

        SeatAllocationService.ClassAvailabilitySnapshot availability =
                seatAllocationService.availability(context.coach(), request.getJourneyDate());

        int confirm = Math.min(availability.availableSeats(), passengers);
        int remaining = passengers - confirm;
        int rac = allowRac ? Math.min(availability.racSeatsAvailable(), remaining) : 0;
        int waitlist = passengers - confirm - rac;

        return BookingQuoteDto.builder()
                .trainId(context.train().getId())
                .trainNumber(context.train().getTrainNumber())
                .trainName(context.train().getName())
                .coachId(context.coach().getId())
                .coachCode(context.coach().getCoachCode())
                .seatClass(context.coach().getSeatClass().name())
                .seatClassLabel(context.coach().getSeatClass().getLabel())
                .sourceStationId(context.source().getId())
                .sourceStationName(context.source().getName())
                .destinationStationId(context.destination().getId())
                .destinationStationName(context.destination().getName())
                .journeyDate(request.getJourneyDate())
                .distanceKm(distanceKm)
                .passengers(passengers)
                .farePerPassenger(perPassenger)
                .totalFare(breakdown.getTotalPayable())
                .totalSeats(availability.totalSeats())
                .availableSeats(availability.availableSeats())
                .racSeatsAvailable(availability.racSeatsAvailable())
                .status(availability.status())
                .wouldConfirm(confirm)
                .wouldRac(rac)
                .wouldWaitlist(waitlist)
                .fareBreakdown(breakdown)
                .quotaAvailability(quotaAvailability)
                .build();
    }

    /**
     * Resolves the quota each passenger will actually be booked under, and the concession it earns.
     * <p>
     * This deliberately mirrors {@code SeatAllocationService.allocate}'s decision so the quote and
     * the booking agree. It reads the live snapshot without a lock, so a booking created a moment
     * later may find its quota full - that is exactly why {@code allocate} re-checks and records
     * the truth, and why the fare stored on the booking is the one that was really applied.
     */
    private List<PassengerFare> resolvePassengerFares(List<PassengerDto> details, SeatClass seatClass,
                                                      RoleName actorRole, QuotaService.QuotaSnapshot quotas,
                                                      int fallbackCount) {
        List<PassengerFare> fares = new ArrayList<>();
        if (details == null || details.isEmpty()) {
            // No passenger details: price N anonymous general-quota passengers so the totals stay right.
            for (int i = 1; i <= Math.max(1, fallbackCount); i++) {
                fares.add(PassengerFare.of("Passenger " + i, null, null, QuotaType.GENERAL, 0, null));
            }
            return fares;
        }
        QuotaService.QuotaSnapshot budget = quotas.copy();
        for (PassengerDto detail : details) {
            QuotaType requested = QuotaService.parse(detail.getQuota());
            quotaService.validateEligibility(requested, detail, actorRole, quotas);
            QuotaService.QuotaDecision decision = quotaService.decide(requested, budget, true);
            // Only a confirmed seat consumes quota, so the quote assumes the best case and lets the
            // allocation engine have the final word.
            budget.consume(decision.quota());
            fares.add(PassengerFare.of(detail.getFullName(), detail.getAge(), detail.getGender(),
                    decision.quota(), quotaService.discountFor(seatClass, decision.quota()),
                    decision.note()));
        }
        return fares;
    }

    // ------------------------------------------------------------------ create

    /**
     * Creates a booking and holds inventory for a short payment window.
     * <p>
     * When {@code idempotencyKey} is supplied, replaying the same key returns the booking created the
     * first time instead of creating a second one.
     */
    @Transactional
    public BookingResponseDto createBooking(Long userId, CreateBookingRequest request, String idempotencyKey) {
        String key = normalizeKey(idempotencyKey);
        if (key != null) {
            Booking existing = bookingRepository.findByIdempotencyKey(key).orElse(null);
            if (existing != null) {
                if (!existing.getUser().getId().equals(userId)) {
                    // A key must never leak the existence of somebody else's booking.
                    throw new ConflictException("This booking request has already been submitted");
                }
                log.debug("Idempotent replay of booking creation for key {}", key);
                return toDto(existing);
            }
        }

        validatePassengers(request);
        JourneyContext context = resolveJourney(request.getTrainId(), request.getCoachId(),
                request.getSourceStationId(), request.getDestinationStationId(), request.getJourneyDate());

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        boolean duplicate = !bookingRepository.findLiveBookingsForUserAndJourney(
                userId, context.train().getId(), request.getJourneyDate(), LIVE_STATUSES).isEmpty();
        if (duplicate) {
            throw new ConflictException("You already have a live booking for this train on "
                    + request.getJourneyDate() + ". Cancel or let it expire before booking again.");
        }

        int passengers = request.getPassengers().size();
        int distanceKm = context.segment().distanceKm();
        BigDecimal perPassenger =
                fareCalculationService.calculateFarePerPassenger(context.coach(), distanceKm);
        boolean allowRac = request.getAllowRac() == null || request.getAllowRac();

        // Fail fast on an ineligible quota before anything is written. The authoritative check runs
        // again inside allocate(), under the class-wide lock, because the split can change between
        // this read and the allocation.
        QuotaService.QuotaSnapshot preview = quotaService.snapshot(context.coach(), request.getJourneyDate());
        for (PassengerDto passenger : request.getPassengers()) {
            quotaService.validateEligibility(QuotaService.parse(passenger.getQuota()), passenger,
                    user.getRole(), preview);
        }

        Booking booking = Booking.builder()
                .bookingReference(generateBookingReference())
                .user(user)
                .train(context.train())
                .coach(context.coach())
                .sourceStation(context.source())
                .destinationStation(context.destination())
                .journeyDate(request.getJourneyDate())
                .status(BookingStatus.PAYMENT_PENDING)
                // Provisional: the real, itemised total is computed from the quotas the allocation
                // actually assigned, once the passenger rows exist.
                .totalFare(perPassenger.multiply(BigDecimal.valueOf(passengers))
                        .setScale(2, java.math.RoundingMode.HALF_UP))
                .distanceKm(distanceKm)
                .farePerPassenger(perPassenger)
                .idempotencyKey(key)
                .paymentDeadline(LocalDateTime.now().plusMinutes(bookingProperties.getHoldMinutes()))
                .build();

        try {
            booking = bookingRepository.saveAndFlush(booking);
        } catch (DataIntegrityViolationException ex) {
            throw new ConflictException("This booking request has already been submitted");
        }

        // Allocation fixes the quota on every passenger row; the fare is then derived from those rows,
        // so the price charged can never disagree with the quota actually granted.
        seatAllocationService.allocate(booking, request.getPassengers(), allowRac, user.getRole());
        applyFareSnapshot(booking, distanceKm);
        seatAllocationService.refreshBookingStatus(booking);
        booking = bookingRepository.save(booking);

        long confirmed = booking.getPassengers().stream()
                .filter(p -> p.getPassengerStatus() == PassengerStatus.CONFIRMED).count();
        long rac = booking.getPassengers().stream()
                .filter(p -> p.getPassengerStatus() == PassengerStatus.RAC).count();
        long waitlisted = booking.getPassengers().stream()
                .filter(p -> p.getPassengerStatus() == PassengerStatus.WAITLISTED).count();

        StringBuilder breakdown = new StringBuilder()
                .append(confirmed).append(" confirmed");
        if (rac > 0) {
            breakdown.append(", ").append(rac).append(" on RAC");
        }
        if (waitlisted > 0) {
            breakdown.append(", ").append(waitlisted).append(" waitlisted");
        }
        notificationService.notify(user, NotificationType.BOOKING_CONFIRMATION,
                "Booking created",
                "Booking " + booking.getBookingReference() + " for " + context.train().getName()
                        + " on " + request.getJourneyDate() + " is awaiting payment (" + breakdown
                        + "). Seats are held for " + bookingProperties.getHoldMinutes() + " minutes.");

        domainEventPublisher.publish(PlatformEvent.of("BOOKING_CREATED", booking.getBookingReference(),
                Map.of("train", context.train().getTrainNumber(), "passengers", String.valueOf(booking.getPassengers().size()))));

        return toDto(booking);
    }

    // ------------------------------------------------------------------ payment

    /**
     * Processes (or replays) the mock payment for a booking.
     * <p>
     * Availability is re-checked under the coach lock first: a hold can lapse between creation and
     * payment, in which case the affected passengers drop to RAC or the waitlist instead of the
     * passenger being promised a seat that no longer exists.
     */
    @Transactional
    public BookingResponseDto processPayment(Long userId, Long bookingId, ProcessPaymentRequest request,
                                            String idempotencyKey) {
        String key = normalizeKey(idempotencyKey);

        if (key != null) {
            Payment replay = paymentRepository.findByIdempotencyKey(key).orElse(null);
            if (replay != null) {
                Booking booking = replay.getBooking();
                if (!booking.getUser().getId().equals(userId)) {
                    throw new ConflictException("This payment request has already been processed");
                }
                log.debug("Idempotent replay of payment for key {}", key);
                return toDto(booking);
            }
        }

        Booking booking = getOwnedBooking(userId, bookingId);
        if (booking.getStatus() == BookingStatus.EXPIRED) {
            throw new ConflictException("This booking expired because payment was not completed in time. "
                    + "Please create a new booking.");
        }
        if (booking.getStatus() != BookingStatus.PAYMENT_PENDING && booking.getStatus() != BookingStatus.PAYMENT_FAILED) {
            throw new ConflictException("This booking is not awaiting payment");
        }

        seatAllocationService.revalidateBeforePayment(booking);
        seatAllocationService.refreshBookingStatus(booking);

        boolean success = request.getSimulateSuccess() == null || request.getSimulateSuccess();
        String txnRef = paymentLedgerService.nextTransactionReference();

        Payment existingPayment = paymentRepository.findByBookingId(booking.getId()).orElse(null);
        Payment payment = existingPayment != null ? existingPayment : new Payment();
        payment.setBooking(booking);
        payment.setMethod(request.getMethod().toUpperCase());
        payment.setAmount(booking.getTotalFare());
        payment.setStatus(success ? PaymentStatus.SUCCESS : PaymentStatus.FAILED);
        payment.setTransactionReference(txnRef);
        payment.setIdempotencyKey(key);
        payment = paymentRepository.saveAndFlush(payment);

        // Phase 5: the attempt is recorded for BOTH outcomes. Writing only the success is what
        // previously made a retried payment look like a single, first-time, trouble-free one.
        paymentLedgerService.recordAttempt(booking, payment.getMethod(), payment.getAmount(),
                payment.getStatus(), txnRef, key);

        // The queue as it stands *before* this booking's money arrives. An unpaid booking is not in
        // line for a seat, so this is exactly the set of passengers whose rank this payment can push
        // down — and, once the status flips to WAITLISTED, the ones it can add.
        Map<Long, Integer> queueBeforePayment =
                waitlistQueueService.ranksFor(booking.getCoach().getId(), booking.getJourneyDate());

        if (success) {
            seatAllocationService.confirmHolds(booking);
            seatAllocationService.recomputeBookingStatus(booking);
            booking.setPaymentDeadline(null);
        } else {
            // A failed payment keeps the seat hold alive so the passenger can retry, but the
            // booking is no longer "awaiting payment" - it is explicitly failed.
            booking.setStatus(BookingStatus.PAYMENT_FAILED);
            booking.setPaymentDeadline(LocalDateTime.now().plusMinutes(bookingProperties.getHoldMinutes()));
        }
        booking = bookingRepository.save(booking);

        if (success) {
            // Now that the booking is really WAITLISTED / RAC, its passengers belong in the queue:
            // renumber it so the stored position is the true rank, and tell them where they landed.
            // A failed payment changes nothing here, because an unpaid booking is not queued.
            waitlistQueueService.reconcile(
                    booking.getCoach().getId(), booking.getJourneyDate(), queueBeforePayment);
        }

        String passengerSummary = booking.getPassengers().stream()
                .map(p -> p.getFullName() + " — " + p.getPassengerStatus().name())
                .reduce((a, b) -> a + ", " + b)
                .orElse("");

        notificationService.notify(booking.getUser(), NotificationType.PAYMENT_STATUS,
                success ? "Payment successful" : "Payment failed",
                success
                        ? "Payment for booking " + booking.getBookingReference() + " was successful. Status: "
                        + booking.getStatus().name() + ". " + passengerSummary
                        : "Payment for booking " + booking.getBookingReference() + " failed. Please try again.");

        // A settlement and a failure are different facts, and the monitoring counter keeps them
        // apart so the failure rate is a number an operator can alert on rather than an absence.
        domainEventPublisher.publish(PlatformEvent.of(
                success ? "PAYMENT_SETTLED" : "PAYMENT_FAILED", booking.getBookingReference()));

        return toDto(booking);
    }

    // ------------------------------------------------------------------ cancellation

    /**
     * Cancels the entire booking.
     * <p>
     * This is the Phase 2-4 endpoint, kept exactly as it was: same request body, same response, same
     * refusal to cancel something that was never paid for. The only change is that the refund is now
     * computed by {@link RefundService} from the configured tiers instead of by an inline chain of
     * {@code if}s, which is what lets the passenger be shown the same figures before they commit.
     * <p>
     * Cancelling a subset of the passengers is a different, additive path - see
     * {@code CancellationService} - so that a passenger dropping one traveller does not have to
     * cancel everybody and rebook.
     */
    @Transactional
    public BookingResponseDto cancelBooking(Long userId, Long bookingId, CancelBookingRequest request) {
        Booking booking = getOwnedBooking(userId, bookingId);

        // Throws with the Phase 2-4 message when the booking is already cancelled, already expired,
        // or was never paid for, so the behaviour every existing client knows is unchanged.
        RefundService.RefundComputation refund = refundService.computeForWholeBooking(booking);

        Cancellation cancellation = cancellationRepository.save(Cancellation.builder()
                .booking(booking)
                .reason(request.getReason())
                .refundAmount(refund.totalRefund())
                .refundStatus(RefundStatus.PROCESSED)
                .scope(CancellationScope.FULL)
                .fareRefunded(refund.fareRefunded())
                .gstRefunded(refund.gstRefunded())
                .platformFeeRefunded(refund.platformFeeRefunded())
                .cancellationCharge(refund.charge())
                .refundPercent(BigDecimal.valueOf(refund.tier().getRefundPercent()))
                .daysBeforeJourney(refund.daysBeforeJourney())
                .refundReference(refundService.nextRefundReference())
                .processedAt(LocalDateTime.now())
                .build());

        seatAllocationService.releaseAll(booking, PassengerStatus.CANCELLED);
        booking.setStatus(BookingStatus.CANCELLED);
        booking = bookingRepository.save(booking);

        notificationService.notify(booking.getUser(), NotificationType.CANCELLATION,
                "Booking cancelled",
                "Booking " + booking.getBookingReference() + " was cancelled. A simulated demo refund of INR "
                        + refund.totalRefund() + " has been issued at the '" + refund.tier().getLabel()
                        + "' tier, which is " + refund.fareRefunded() + " of fare, " + refund.gstRefunded()
                        + " of GST and " + refund.platformFeeRefunded() + " of the platform fee. Reference "
                        + cancellation.getRefundReference() + ".");

        domainEventPublisher.publish(PlatformEvent.of("BOOKING_CANCELLED", booking.getBookingReference(),
                Map.of("refundReference", cancellation.getRefundReference())));

        return toDto(booking);
    }

    // ------------------------------------------------------------------ reads

    /**
     * Asserts the booking belongs to the caller. Throws {@link ResourceNotFoundException} if it does
     * not, using the same 404 semantics we use elsewhere for cross-user access.
     */
    @Transactional(readOnly = true)
    public void assertOwnedBy(Long userId, Long bookingId) {
        if (!bookingRepository.existsByIdAndUserId(bookingId, userId)) {
            throw new ResourceNotFoundException("Booking not found");
        }
    }

    @Transactional(readOnly = true)
    public List<BookingResponseDto> getBookingsForUser(Long userId) {
        return bookingRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public BookingResponseDto getBookingDetails(Long userId, Long bookingId) {
        return toDto(getOwnedBooking(userId, bookingId));
    }

    @Transactional(readOnly = true)
    public List<BookingResponseDto> getAllBookings() {
        return bookingRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(this::toDto)
                .toList();
    }

    /**
     * Phase 7: the admin bookings table, paginated and filterable.
     * <p>
     * {@code status} arrives as text because it is a query parameter; an unknown value is a client
     * error and is rejected rather than silently ignored, since a filter that quietly matches
     * everything is worse than a 400.
     */
    @Transactional(readOnly = true)
    public PageResponse<BookingResponseDto> searchAllBookings(String search, String status, Long trainId,
                                                              Long userId, LocalDate journeyFrom,
                                                              LocalDate journeyTo, int page, int size) {
        BookingStatus statusFilter = null;
        if (status != null && !status.isBlank()) {
            try {
                statusFilter = BookingStatus.valueOf(status.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new BadRequestException("Unknown booking status: " + status);
            }
        }
        int pageSize = Math.min(Math.max(1, size), AuditService.MAX_PAGE_SIZE);
        return PageResponse.of(bookingRepository.search(
                search != null && !search.isBlank() ? "%" + search.trim().toLowerCase() + "%" : null,
                statusFilter, trainId, userId, journeyFrom, journeyTo,
                PageRequest.of(Math.max(0, page), pageSize, Sort.by(Sort.Direction.DESC, "createdAt", "id"))),
                this::toDto);
    }

    // ------------------------------------------------------------------ internals

    /**
     * Prices a booking from the quotas its passengers were actually assigned, and stores the result
     * as the booking's immutable fare snapshot.
     * <p>
     * Called after {@code allocate()}, never before: the quota decides the concession, and the quota
     * is only known once allocation has decided which seat - if any - each passenger got.
     */
    private FareBreakdownDto applyFareSnapshot(Booking booking, int distanceKm) {
        List<PassengerFare> fares = new ArrayList<>();
        for (BookingPassenger passenger : booking.getPassengers()) {
            fares.add(PassengerFare.of(
                    passenger.getFullName(),
                    passenger.getAge(),
                    passenger.getGender(),
                    passenger.getQuota() == null ? QuotaType.GENERAL : passenger.getQuota(),
                    passenger.getQuotaDiscountPercent() == null ? 0 : passenger.getQuotaDiscountPercent(),
                    passenger.getQuotaNote()));
        }
        FareCalculationService.Tariff tariff =
                fareCalculationService.resolveTariff(booking.getCoach().getSeatClass());
        FareBreakdownDto breakdown = fareCalculationService.buildBreakdown(
                booking.getCoach(), distanceKm, booking.getDistanceKm(), fares, tariff);
        fareCalculationService.applySnapshot(booking, breakdown);

        // The breakdown preserves passenger order, so each passenger's net fare lands on its own row
        // and is visible on the ticket without re-deriving it.
        List<BookingPassenger> rows = booking.getPassengers();
        for (int i = 0; i < rows.size() && i < breakdown.getPassengers().size(); i++) {
            rows.get(i).setFareAmount(breakdown.getPassengers().get(i).getPayableFare());
        }
        return breakdown;
    }

    private void validatePassengers(CreateBookingRequest request) {
        scheduleValidator.validateJourneyDate(request.getJourneyDate());
        if (Objects.equals(request.getSourceStationId(), request.getDestinationStationId())) {
            throw new BadRequestException("Source and destination stations cannot be the same");
        }
        int max = bookingProperties.getMaxPassengersPerBooking();
        if (request.getPassengers() == null || request.getPassengers().isEmpty()) {
            throw new BadRequestException("At least one passenger is required");
        }
        if (request.getPassengers().size() > max) {
            throw new BadRequestException("A maximum of " + max + " passengers can be booked at once");
        }
        for (int i = 0; i < request.getPassengers().size(); i++) {
            for (int j = i + 1; j < request.getPassengers().size(); j++) {
                if (request.getPassengers().get(i).getFullName().trim()
                        .equalsIgnoreCase(request.getPassengers().get(j).getFullName().trim())) {
                    throw new BadRequestException("Passenger "
                            + (j + 1) + " has the same name as passenger " + (i + 1)
                            + ". Every passenger must be listed separately.");
                }
            }
        }
    }

    /** Loads and validates train, coach, stations, route order, service day and segment distance. */
    private JourneyContext resolveJourney(Long trainId, Long coachId, Long sourceStationId,
                                          Long destinationStationId, LocalDate journeyDate) {
        scheduleValidator.validateJourneyDate(journeyDate);

        Train train = trainRepository.findById(trainId)
                .orElseThrow(() -> new ResourceNotFoundException("Train not found"));
        scheduleValidator.validateServiceDay(train, journeyDate);

        Coach coach = coachRepository.findById(coachId)
                .orElseThrow(() -> new ResourceNotFoundException("Selected class is not available on this train"));
        if (!coach.getTrain().getId().equals(train.getId())) {
            throw new BadRequestException("Selected class does not belong to this train");
        }
        if (!Boolean.TRUE.equals(coach.getActive())) {
            throw new BadRequestException("This class is currently closed for bookings on this train");
        }

        Station source = stationRepository.findById(sourceStationId)
                .orElseThrow(() -> new ResourceNotFoundException("Source station not found"));
        Station destination = stationRepository.findById(destinationStationId)
                .orElseThrow(() -> new ResourceNotFoundException("Destination station not found"));

        TrainScheduleValidator.Segment segment =
                scheduleValidator.resolveSegment(train, sourceStationId, destinationStationId);

        return new JourneyContext(train, coach, source, destination, segment);
    }

    private Booking getOwnedBooking(Long userId, Long bookingId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found"));
        if (!booking.getUser().getId().equals(userId)) {
            // Same 404 as a genuinely missing booking: never confirm that somebody else's booking exists.
            throw new ResourceNotFoundException("Booking not found");
        }
        return booking;
    }

    private static String normalizeKey(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > 80) {
            throw new BadRequestException("Idempotency key must be at most 80 characters");
        }
        return trimmed;
    }

    private String generateBookingReference() {
        String ref;
        do {
            StringBuilder sb = new StringBuilder("YR");
            for (int i = 0; i < 8; i++) {
                sb.append(REF_ALPHABET[RANDOM.nextInt(REF_ALPHABET.length)]);
            }
            ref = sb.toString();
        } while (bookingRepository.findByBookingReference(ref).isPresent());
        return ref;
    }

    private BookingResponseDto toDto(Booking booking) {
        Payment payment = paymentRepository.findByBookingId(booking.getId()).orElse(null);
        // Rebuilt from the stored snapshot, never re-priced from the live tariff.
        FareBreakdownDto breakdown = fareCalculationService.rebuildFromSnapshot(booking);
        List<PassengerResultDto> passengers = passengerMapper.describe(booking);

        return BookingResponseDto.builder()
                .id(booking.getId())
                .bookingReference(booking.getBookingReference())
                .trainName(booking.getTrain().getName())
                .trainNumber(booking.getTrain().getTrainNumber())
                .sourceStationName(booking.getSourceStation().getName())
                .destinationStationName(booking.getDestinationStation().getName())
                .journeyDate(booking.getJourneyDate())
                .seatClass(booking.getCoach().getSeatClass().getLabel())
                .status(booking.getStatus().name())
                .totalFare(booking.getTotalFare())
                .distanceKm(booking.getDistanceKm())
                .farePerPassenger(booking.getFarePerPassenger())
                .fareBreakdown(breakdown)
                .paymentDeadline(booking.getPaymentDeadline())
                .createdAt(booking.getCreatedAt())
                .passengers(passengers)
                .payment(payment == null ? null : PaymentDto.builder()
                        .method(payment.getMethod())
                        .amount(payment.getAmount())
                        .status(payment.getStatus().name())
                        .transactionReference(payment.getTransactionReference())
                        .build())
                .build();
    }

    private record JourneyContext(Train train, Coach coach, Station source, Station destination,
                                  TrainScheduleValidator.Segment segment) {
    }
}

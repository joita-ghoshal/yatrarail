package com.yatrarail.service;

import com.yatrarail.config.BookingProperties;
import com.yatrarail.dto.booking.PassengerDto;
import com.yatrarail.entity.*;
import com.yatrarail.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * The reservation engine: it decides, under a database lock, which physical seats a booking may
 * take, holds them while payment is pending, releases them on cancellation or payment-window expiry,
 * and promotes the RAC / waitlist queues whenever inventory comes back.
 * <p>
 * Allocation order for a new booking (in passenger order):
 * <ol>
 *   <li>a free seat, preferring the passenger's berth preference, then any free seat;</li>
 *   <li>otherwise an RAC entry, while the coach still has RAC capacity;</li>
 *   <li>otherwise the waitlist.</li>
 * </ol>
 * Every allocation and promotion runs inside a transaction that first takes a
 * {@code SELECT ... FOR UPDATE} lock on the coach row, so two concurrent bookings can never be
 * granted the same seat. The {@code (seat_id, journey_date)} unique constraint on
 * {@code seat_reservations} enforces the same invariant at the database level.
 * <p>
 * <b>Phase 4 — quotas.</b> The lock now covers <i>every active coach of the class</i>, not just the
 * one being booked, because a quota is a per-(train, class) allowance: two passengers booking two
 * different third-AC coaches of the same train are competing for the same senior-citizen slice, and
 * locking only one coach would let them both succeed.
 * <p>
 * Allocation consults one {@link QuotaService.QuotaSnapshot} taken under that lock, so the budget it
 * spends from is a consistent picture of the whole class at the moment of the decision. The rules
 * it follows, all of them disclosed to the passenger on the ticket:
 * <ul>
 *   <li>a confirmed seat always consumes the quota the passenger is booked under, so a quota can
 *       never hand out more seats than its configured capacity;</li>
 *   <li>when a reserved quota is full but general still has room, the passenger is booked under
 *       general at the full fare, with a note saying so;</li>
 *   <li>when the whole class is full, the passenger enters RAC or the waitlist <i>under the quota
 *       they claimed</i> and does not consume quota until they are promoted - so a queued
 *       passenger can never block somebody who could still get a seat.</li>
 * </ul>
 * Because the quota is fixed on the passenger row at allocation time, the fare charged and the
 * quota claimed cannot drift apart: a later promotion re-checks the recorded quota and, if that
 * quota has since filled, simply leaves the passenger queued rather than quietly re-pricing them.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeatAllocationService {

    private final CoachRepository coachRepository;
    private final SeatRepository seatRepository;
    private final SeatReservationRepository seatReservationRepository;
    private final BookingPassengerRepository bookingPassengerRepository;
    private final BookingRepository bookingRepository;
    private final NotificationService notificationService;
    private final WaitlistQueueService waitlistQueueService;
    private final BookingProperties bookingProperties;
    private final QuotaService quotaService;

    /** Booking statuses that still occupy inventory. */
    private static final List<BookingStatus> HOLDING_STATUSES = List.of(
            BookingStatus.PAYMENT_PENDING, BookingStatus.CONFIRMED,
            BookingStatus.RAC, BookingStatus.WAITLISTED);

    // ------------------------------------------------------------------ availability

    /**
     * Read-only availability snapshot for one coach on one journey date. Deliberately takes no
     * coach lock so search stays fast; the result may be momentarily stale under concurrent booking,
     * which is why allocation always re-checks under the lock.
     */
    @Transactional(readOnly = true)
    public ClassAvailabilitySnapshot availability(Coach coach, LocalDate journeyDate) {
        LocalDateTime now = LocalDateTime.now();
        Long coachId = coach.getId();
        List<Seat> seats = seatRepository.findByCoachIdAndActiveTrueOrderBySeatNumberAsc(coachId);
        Set<Long> occupied = new HashSet<>(
                seatReservationRepository.findOccupiedSeatIdsForCoach(coachId, journeyDate, now));
        int free = (int) seats.stream().filter(s -> !occupied.contains(s.getId())).count();
        int racFree = racSlotsFree(coachId, journeyDate, coach.getRacCapacity());
        return new ClassAvailabilitySnapshot(seats.size(), free, racFree, tier(free, racFree));
    }

    private static String tier(int free, int racFree) {
        if (free > 0) {
            return "AVAILABLE";
        }
        return racFree > 0 ? "RAC_AVAILABLE" : "WAITLIST";
    }

    private int racSlotsFree(Long coachId, LocalDate journeyDate, int racCapacity) {
        long racUsed = bookingPassengerRepository
                .countByBookingCoachIdAndBookingJourneyDateAndPassengerStatusInAndBookingStatusIn(
                        coachId, journeyDate, List.of(PassengerStatus.RAC), HOLDING_STATUSES);
        return Math.max(0, racCapacity - (int) racUsed);
    }

    // ------------------------------------------------------------------ allocation

    /**
     * Allocates inventory for a freshly created (unpaid) booking. Must run inside the caller's
     * transaction, after the {@link Booking} has been persisted.
     * <p>
     * Phase 4: each passenger's requested quota is validated, resolved against the class-wide quota
     * budget and written onto the passenger row together with the concession that quota carries.
     * The caller then prices the booking from those rows, which guarantees the fare reflects the
     * quota the passenger actually got rather than the one they asked for.
     *
     * @param actorRole the role of the account making the booking, needed to authorise the staff quota
     */
    @Transactional
    public void allocate(Booking booking, List<PassengerDto> requests, boolean allowRac, RoleName actorRole) {
        Coach coach = lockClassCoaches(booking.getCoach().getTrain().getId(), booking.getCoach().getSeatClass())
                .stream()
                .filter(c -> c.getId().equals(booking.getCoach().getId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Coach disappeared during allocation"));
        LocalDate journeyDate = booking.getJourneyDate();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime deadline = booking.getPaymentDeadline() != null
                ? booking.getPaymentDeadline()
                : now.plusMinutes(bookingProperties.getHoldMinutes());

        QuotaService.QuotaSnapshot quotas = quotaService.snapshot(coach, journeyDate);
        SeatClass seatClass = coach.getSeatClass();

        List<Seat> seats = seatRepository.findByCoachIdAndActiveTrueOrderBySeatNumberAsc(coach.getId());
        Set<Long> occupied = new HashSet<>(
                seatReservationRepository.findOccupiedSeatIdsForCoach(coach.getId(), journeyDate, now));
        int racFree = allowRac ? racSlotsFree(coach.getId(), journeyDate, coach.getRacCapacity()) : 0;

        Integer nextPosition = nextQueuePosition(coach.getId(), journeyDate);
        List<BookingPassenger> allocated = new ArrayList<>();

        for (PassengerDto request : requests) {
            QuotaType requestedQuota = QuotaService.parse(request.getQuota());
            // Re-validated under the lock: the quote may have been taken before a config change.
            quotaService.validateEligibility(requestedQuota, request, actorRole, quotas);

            Seat seat = pickSeat(normalizePreference(request.getBerthPreference()), seats, occupied);
            QuotaService.QuotaDecision decision =
                    quotaService.decide(requestedQuota, quotas, seat != null);
            QuotaType assignedQuota = decision.quota();

            BookingPassenger passenger = BookingPassenger.builder()
                    .booking(booking)
                    .fullName(request.getFullName().trim())
                    .age(request.getAge())
                    .gender(request.getGender())
                    .berthPreference(normalizePreference(request.getBerthPreference()))
                    .waitlistPosition(nextPosition++)
                    .quota(assignedQuota)
                    .quotaDiscountPercent(quotaService.discountFor(seatClass, assignedQuota))
                    .quotaNote(decision.note())
                    .build();

            if (seat != null && quotas.remainingOf(assignedQuota) > 0) {
                occupied.add(seat.getId());
                quotas.consume(assignedQuota);
                passenger.setSeat(seat);
                passenger.setPassengerStatus(PassengerStatus.CONFIRMED);
            } else {
                seat = null;
                if (racFree > 0) {
                    racFree--;
                    passenger.setPassengerStatus(PassengerStatus.RAC);
                } else {
                    passenger.setPassengerStatus(PassengerStatus.WAITLISTED);
                }
            }

            BookingPassenger saved = bookingPassengerRepository.save(passenger);
            if (seat != null) {
                holdSeat(booking, saved, seat, journeyDate, ReservationStatus.HELD, deadline,
                        assignedQuota);
            }
            allocated.add(saved);
        }

        booking.getPassengers().clear();
        booking.getPassengers().addAll(allocated);
    }

    /**
     * Re-checks inventory for an unpaid booking and re-attempts allocation for anybody who lost
     * their hold. Called when a payment is attempted, because a hold can be swept away between
     * booking creation and payment.
     * <p>
     * Phase 4: a recovered seat must come out of the quota the passenger was originally booked
     * under, since their fare was already priced from it. If that quota is full the passenger stays
     * queued rather than being moved to a different quota, because moving them would change what
     * they have already agreed to pay.
     */
    @Transactional
    public void revalidateBeforePayment(Booking booking) {
        Coach coach = lockClassCoaches(booking.getCoach().getTrain().getId(), booking.getCoach().getSeatClass())
                .stream()
                .filter(c -> c.getId().equals(booking.getCoach().getId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Coach disappeared during payment revalidation"));
        LocalDate journeyDate = booking.getJourneyDate();
        LocalDateTime now = LocalDateTime.now();

        QuotaService.QuotaSnapshot quotas = quotaService.snapshot(coach, journeyDate);

        List<Seat> seats = seatRepository.findByCoachIdAndActiveTrueOrderBySeatNumberAsc(coach.getId());
        Set<Long> occupied = new HashSet<>(
                seatReservationRepository.findOccupiedSeatIdsForCoach(coach.getId(), journeyDate, now));
        int racFree = racSlotsFree(coach.getId(), journeyDate, coach.getRacCapacity());

        for (BookingPassenger passenger : booking.getPassengers()) {
            if (passenger.getPassengerStatus() == PassengerStatus.CONFIRMED
                    && passenger.getSeat() != null
                    && !occupied.contains(passenger.getSeat().getId())) {
                // The hold lapsed. Its quota was already released with the reservation, so the
                // snapshot read above is the authority - nothing to give back here.
                passenger.setSeat(null);
                passenger.setPassengerStatus(PassengerStatus.WAITLISTED);
            }
        }

        Integer nextPosition = nextQueuePosition(coach.getId(), journeyDate);
        boolean changed = false;
        for (BookingPassenger passenger : booking.getPassengers()) {
            if (passenger.getPassengerStatus() == PassengerStatus.CONFIRMED) {
                continue;
            }
            QuotaType quota = passenger.getQuota() == null ? QuotaType.GENERAL : passenger.getQuota();
            Seat seat = pickSeat(passenger.getBerthPreference(), seats, occupied);
            if (seat != null && quotas.remainingOf(quota) > 0) {
                occupied.add(seat.getId());
                quotas.consume(quota);
                passenger.setSeat(seat);
                passenger.setPassengerStatus(PassengerStatus.CONFIRMED);
                passenger.setWaitlistPosition(nextPosition++);
                holdSeat(booking, passenger, seat, journeyDate, ReservationStatus.HELD,
                        booking.getPaymentDeadline() != null
                                ? booking.getPaymentDeadline()
                                : now.plusMinutes(bookingProperties.getHoldMinutes()),
                        quota);
                changed = true;
            } else if (passenger.getPassengerStatus() == PassengerStatus.WAITLISTED && racFree > 0) {
                racFree--;
                passenger.setPassengerStatus(PassengerStatus.RAC);
                passenger.setWaitlistPosition(nextPosition++);
                changed = true;
            }
        }
        if (changed) {
            bookingPassengerRepository.saveAll(booking.getPassengers());
        }
    }

    /** Turns every HELD reservation of a booking into a CONFIRMED one after a successful payment. */
    @Transactional
    public void confirmHolds(Booking booking) {
        for (BookingPassenger passenger : booking.getPassengers()) {
            if (passenger.getSeat() == null) {
                continue;
            }
            seatReservationRepository.findByBookingPassengerId(passenger.getId()).ifPresent(reservation -> {
                reservation.setStatus(ReservationStatus.CONFIRMED);
                reservation.setExpiresAt(null);
                seatReservationRepository.save(reservation);
            });
        }
    }

    /** Frees every seat held or confirmed by a booking and promotes the RAC / waitlist queue. */
    @Transactional
    public void releaseAll(Booking booking, PassengerStatus finalStatus) {
        releasePassengers(booking, new ArrayList<>(booking.getPassengers()), finalStatus);
    }

    /**
     * Frees the seats of specific passengers and promotes the RAC / waitlist queue.
     * <p>
     * Phase 5: a booking can be cancelled one passenger at a time, so releasing inventory cannot be
     * an all-or-nothing operation any more. The freed seat goes straight back into the queue exactly
     * as it did on a whole-booking cancellation — a partial cancellation is a real seat coming back,
     * and a waitlisted passenger on the same train should not have to wait for one.
     * <p>
     * A passenger who is already {@link PassengerStatus#CANCELLED} or {@link PassengerStatus#EXPIRED}
     * is left alone: re-releasing their row would resurrect a status that is supposed to be final.
     */
    @Transactional
    public void releasePassengers(Booking booking, List<BookingPassenger> targets, PassengerStatus finalStatus) {
        // Captured before anything moves, so "your place improved" is a comparison against the live
        // queue rather than a difference between two numbers off a column that was never renumbered.
        Map<Long, Integer> queueBefore =
                waitlistQueueService.ranksFor(booking.getCoach().getId(), booking.getJourneyDate());
        for (BookingPassenger passenger : targets) {
            seatReservationRepository.deleteByBookingPassengerId(passenger.getId());
            if (passenger.getPassengerStatus() != PassengerStatus.CANCELLED
                    && passenger.getPassengerStatus() != PassengerStatus.EXPIRED) {
                passenger.setPassengerStatus(finalStatus);
                passenger.setSeat(null);
            }
        }
        bookingPassengerRepository.saveAll(booking.getPassengers());
        promoteQueue(booking.getCoach().getId(), booking.getJourneyDate());
        waitlistQueueService.reconcile(booking.getCoach().getId(), booking.getJourneyDate(), queueBefore);
    }

    // ------------------------------------------------------------------ promotion

    /**
     * Fills freed inventory from the RAC / waitlist queue for one coach + journey date, oldest entry
     * first, and raises a per-passenger notification for every promotion.
     * <p>
     * Priority: an existing RAC entry is upgraded to its own confirmed seat first, then waitlisted
     * passengers take any remaining seat. When no whole seat is free but RAC capacity is open, a
     * waitlisted passenger is offered a berth on RAC.
     * <p>
     * Phase 4: a promotion only happens if the passenger's <i>recorded</i> quota still has room. If
     * that quota has filled up in the meantime the passenger simply stays queued - moving them to a
     * different quota would change the price they already agreed to pay, and over-issuing a
     * reserved quota would break the capacity the administrator configured. The lock covers the
     * whole class so concurrent promotions in sibling coaches cannot jointly exceed a quota either.
     */
    @Transactional
    public void promoteQueue(Long coachId, LocalDate journeyDate) {
        Coach target = coachRepository.findById(coachId).orElse(null);
        if (target == null) {
            return;
        }
        List<Coach> locked = lockClassCoaches(target.getTrain().getId(), target.getSeatClass());
        Coach coach = locked.stream()
                .filter(c -> c.getId().equals(coachId))
                .findFirst()
                .orElse(null);
        if (coach == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        QuotaService.QuotaSnapshot quotas = quotaService.snapshot(coach, journeyDate);
        List<Seat> seats = seatRepository.findByCoachIdAndActiveTrueOrderBySeatNumberAsc(coachId);
        Set<Long> occupied = new HashSet<>(
                seatReservationRepository.findOccupiedSeatIdsForCoach(coachId, journeyDate, now));
        List<Seat> free = seats.stream().filter(s -> !occupied.contains(s.getId())).toList();
        int racFree = racSlotsFree(coachId, journeyDate, coach.getRacCapacity());

        List<BookingPassenger> racQueue = bookingPassengerRepository.findPromotionQueue(
                coachId, journeyDate, List.of(PassengerStatus.RAC));
        List<BookingPassenger> waitlistQueue = bookingPassengerRepository.findPromotionQueue(
                coachId, journeyDate, List.of(PassengerStatus.WAITLISTED));

        List<Promotion> promotions = new ArrayList<>();
        Set<Long> promoted = new HashSet<>();
        int seatCursor = 0;

        for (BookingPassenger passenger : racQueue) {
            if (seatCursor >= free.size()) {
                break;
            }
            QuotaType quota = quotaOf(passenger);
            if (quotas.remainingOf(quota) <= 0) {
                continue;
            }
            Seat seat = free.get(seatCursor++);
            occupied.add(seat.getId());
            quotas.consume(quota);
            promote(passenger, seat, PassengerStatus.CONFIRMED, journeyDate, coach, quota, promotions);
            promoted.add(passenger.getId());
        }

        for (BookingPassenger passenger : waitlistQueue) {
            if (seatCursor >= free.size()) {
                break;
            }
            QuotaType quota = quotaOf(passenger);
            if (quotas.remainingOf(quota) <= 0) {
                continue;
            }
            Seat seat = free.get(seatCursor++);
            occupied.add(seat.getId());
            quotas.consume(quota);
            promote(passenger, seat, PassengerStatus.CONFIRMED, journeyDate, coach, quota, promotions);
            promoted.add(passenger.getId());
        }

        if (racFree > 0) {
            for (BookingPassenger passenger : waitlistQueue) {
                if (racFree == 0) {
                    break;
                }
                if (promoted.contains(passenger.getId())) {
                    continue;
                }
                racFree--;
                // An RAC berth is not a seat, so it does not consume a quota slot.
                promote(passenger, null, PassengerStatus.RAC, journeyDate, coach, quotaOf(passenger), promotions);
                promoted.add(passenger.getId());
            }
        }

        if (promotions.isEmpty()) {
            return;
        }
        List<BookingPassenger> changed = promotions.stream()
                .map(Promotion::passenger).distinct().toList();
        bookingPassengerRepository.saveAll(changed);
        changed.stream().map(BookingPassenger::getBooking).distinct()
                .forEach(booking -> {
                    refreshBookingStatus(booking);
                    bookingRepository.save(booking);
                });
        for (Promotion promotion : promotions) {
            Booking booking = promotion.booking();
            notificationService.notify(booking.getUser(), NotificationType.WAITLIST_UPDATE,
                    promotion.status() == PassengerStatus.CONFIRMED ? "Waitlist confirmed" : "Now on RAC",
                    promotion.status() == PassengerStatus.CONFIRMED
                            ? "Good news! " + promotion.passenger().getFullName() + " has been allocated a seat on "
                            + booking.getTrain().getName() + " (#" + booking.getTrain().getTrainNumber() + ") on "
                            + promotion.journeyDate() + " for booking " + booking.getBookingReference() + "."
                            : promotion.passenger().getFullName() + " is now on RAC (Reservation Against Cancellation) for "
                            + booking.getTrain().getName() + " (#" + booking.getTrain().getTrainNumber() + ") on "
                            + promotion.journeyDate() + " for booking " + booking.getBookingReference()
                            + ". A seat is guaranteed once a confirmed booking is cancelled.");
        }
        log.info("Promoted {} passenger entries on coach {} for {}", promotions.size(), coachId, journeyDate);
    }

    private void promote(BookingPassenger passenger, Seat seat, PassengerStatus newStatus,
                         LocalDate journeyDate, Coach coach, QuotaType quota,
                         List<Promotion> promotions) {
        passenger.setSeat(seat);
        passenger.setPassengerStatus(newStatus);
        if (seat != null) {
            seatReservationRepository.save(SeatReservation.builder()
                    .seat(seat)
                    .bookingPassenger(passenger)
                    .journeyDate(journeyDate)
                    .status(ReservationStatus.CONFIRMED)
                    .expiresAt(null)
                    .coachId(coach.getId())
                    .trainId(coach.getTrain().getId())
                    .quota(quota)
                    .build());
        }
        promotions.add(new Promotion(passenger, passenger.getBooking(), newStatus, journeyDate));
    }

    private static QuotaType quotaOf(BookingPassenger passenger) {
        return passenger.getQuota() == null ? QuotaType.GENERAL : passenger.getQuota();
    }

    /**
     * Recomputes a booking's aggregate status from its passengers, unless the booking is still
     * waiting on payment. A booking is only allowed to become CONFIRMED / RAC / WAITLISTED once
     * its money has actually arrived, so the payment states are a hard stop here.
     * <p>
     * Mutates the booking; does not save it.
     */
    public void refreshBookingStatus(Booking booking) {
        if (booking.getStatus() == BookingStatus.PAYMENT_PENDING
                || booking.getStatus() == BookingStatus.PAYMENT_FAILED) {
            return;
        }
        recomputeBookingStatus(booking);
    }

    /**
     * Same recomputation, but without the payment-state guard. Used once a payment succeeds, so the
     * booking can leave PAYMENT_PENDING and adopt the status implied by its passengers.
     */
    public void recomputeBookingStatus(Booking booking) {
        BookingStatus current = booking.getStatus();
        if (current == BookingStatus.CANCELLED || current == BookingStatus.EXPIRED) {
            return;
        }
        List<PassengerStatus> statuses = booking.getPassengers().stream()
                .map(BookingPassenger::getPassengerStatus)
                .toList();
        if (statuses.isEmpty()) {
            return;
        }
        BookingStatus next;
        if (statuses.contains(PassengerStatus.WAITLISTED)) {
            next = BookingStatus.WAITLISTED;
        } else if (statuses.stream().anyMatch(s -> s == PassengerStatus.RAC)) {
            next = BookingStatus.RAC;
        } else if (statuses.stream().allMatch(s -> s == PassengerStatus.CONFIRMED)) {
            next = BookingStatus.CONFIRMED;
        } else {
            next = current;
        }
        if (next != current) {
            booking.setStatus(next);
        }
    }

    // ------------------------------------------------------------------ expiry

    /**
     * Expires a single unpaid booking whose payment window has elapsed. Idempotent: returns false
     * when the booking is not in a state that can expire. Callers should invoke this from a different
     * bean so the {@code @Transactional} proxy applies.
     */
    @Transactional
    public boolean expireBooking(Long bookingId) {
        Booking booking = bookingRepository.findById(bookingId).orElse(null);
        if (booking == null || booking.getStatus() != BookingStatus.PAYMENT_PENDING) {
            return false;
        }
        if (booking.getPaymentDeadline() != null && booking.getPaymentDeadline().isAfter(LocalDateTime.now())) {
            return false;
        }
        releaseAll(booking, PassengerStatus.EXPIRED);
        booking.setStatus(BookingStatus.EXPIRED);
        booking.setPaymentDeadline(null);
        bookingRepository.save(booking);
        notificationService.notify(booking.getUser(), NotificationType.BOOKING_EXPIRED,
                "Booking expired",
                "Booking " + booking.getBookingReference() + " was not paid for within "
                        + bookingProperties.getHoldMinutes()
                        + " minutes, so the held seats were released and you can book again.");
        log.info("Expired unpaid booking {}", booking.getBookingReference());
        return true;
    }

    /** Ids of unpaid bookings whose payment window has elapsed. */
    @Transactional(readOnly = true)
    public Set<Long> findOverduePendingBookingIds(LocalDateTime now) {
        return bookingRepository
                .findByStatusInAndPaymentDeadlineBefore(List.of(BookingStatus.PAYMENT_PENDING), now)
                .stream()
                .map(Booking::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Phase 4: locks <i>every</i> active coach of one class, ordered by id.
     * <p>
     * A quota is a per-(train, class) allowance, so a booking in coach A and a booking in coach B
     * draw from the same senior-citizen slice. Locking only the booked coach would let both
     * succeed and over-issue the quota; locking all of them serialises the decision. The rows are
     * locked in a deterministic order (by id) so two transactions taking overlapping class locks
     * cannot deadlock.
     */
    private List<Coach> lockClassCoaches(Long trainId, SeatClass seatClass) {
        return coachRepository.findByTrainAndClassForUpdate(trainId, seatClass);
    }

    private void holdSeat(Booking booking, BookingPassenger passenger, Seat seat, LocalDate journeyDate,
                          ReservationStatus status, LocalDateTime expiresAt, QuotaType quota) {
        seatReservationRepository.save(SeatReservation.builder()
                .seat(seat)
                .bookingPassenger(passenger)
                .journeyDate(journeyDate)
                .status(status)
                .expiresAt(expiresAt)
                .coachId(booking.getCoach().getId())
                .trainId(booking.getTrain().getId())
                .quota(quota)
                .build());
    }

    private Integer nextQueuePosition(Long coachId, LocalDate journeyDate) {
        Integer max = bookingPassengerRepository.findMaxWaitlistPosition(coachId, journeyDate);
        return (max == null ? 0 : max) + 1;
    }

    private static String normalizePreference(String raw) {
        if (raw == null || raw.isBlank() || "NO_PREFERENCE".equalsIgnoreCase(raw.trim())) {
            return null;
        }
        return raw.trim().toUpperCase(Locale.ROOT);
    }

    /** Free seat matching the requested berth, else any free seat, else null. */
    private Seat pickSeat(String berthPreference, List<Seat> seats, Set<Long> occupied) {
        Seat any = null;
        for (Seat seat : seats) {
            if (occupied.contains(seat.getId())) {
                continue;
            }
            if (berthPreference != null && berthPreference.equalsIgnoreCase(seat.getBerthType())) {
                return seat;
            }
            if (any == null) {
                any = seat;
            }
        }
        return any;
    }

    private record Promotion(BookingPassenger passenger, Booking booking, PassengerStatus status,
                             LocalDate journeyDate) {
    }

    /** Immutable availability numbers for one coach on one journey date. */
    public record ClassAvailabilitySnapshot(int totalSeats, int availableSeats, int racSeatsAvailable,
                                            String status) {
        public boolean hasConfirmedSeat() {
            return availableSeats > 0;
        }

        public boolean hasRac() {
            return racSeatsAvailable > 0;
        }
    }
}

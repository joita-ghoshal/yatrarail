package com.yatrarail.service;

import com.yatrarail.entity.Booking;
import com.yatrarail.entity.BookingPassenger;
import com.yatrarail.entity.NotificationType;
import com.yatrarail.entity.PassengerStatus;
import com.yatrarail.repository.BookingPassengerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps the waitlist's <i>ordering</i> honest and tells the people in it when it changes.
 * <p>
 * <b>What was wrong.</b> {@code waitlist_position} was stamped once, at allocation time, from a
 * running counter — and it was stamped on <em>every</em> passenger, including the ones who got a
 * seat immediately, so the counter climbed by three for a booking where all three travelled. It was
 * never renumbered afterwards. The queue <i>ordering</i> stayed correct, because promotion reads the
 * rows sorted by that column and the gaps fall out of the sort, but the number a passenger was shown
 * was not their place in the queue: a first booking on a busy train could read "position 41" while
 * being effectively last in line, and a passenger at the front of a queue that had shrunk from nine
 * to three still read "position 7". Any "you have moved up" message built on that number would have
 * been fiction.
 * <p>
 * <b>What this does instead.</b> The rank is <i>derived</i> from the queue's current order and written
 * back, so the stored column and the real place in line cannot drift. Ranks are compared before and
 * after a change rather than read off the column, so even a stale starting value cannot produce a
 * wrong claim about movement — a passenger is only ever told they moved up when their rank in the
 * live queue genuinely got better.
 * <p>
 * <b>Whose queue.</b> The promotable queue for one coach and journey date: RAC entries first, then
 * waitlisted entries, in the same order promotion consumes them and excluding anything whose money
 * has not arrived. That is deliberate — the number answers "when will I be offered a seat", and an
 * unpaid hold is not yet in line for one.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WaitlistQueueService {

    private final BookingPassengerRepository bookingPassengerRepository;
    private final NotificationService notificationService;

    /** The queue in the order seats will actually be offered. */
    @Transactional(readOnly = true)
    public List<BookingPassenger> queueFor(Long coachId, LocalDate journeyDate) {
        return bookingPassengerRepository.findPromotionQueue(
                coachId, journeyDate, List.of(PassengerStatus.RAC, PassengerStatus.WAITLISTED));
    }

    /**
     * Current 1-based rank per passenger id, in queue order.
     * <p>
     * Callers capture this <em>before</em> mutating anything and hand it to {@link #reconcile}; that
     * is what makes a movement claim a comparison rather than a guess.
     */
    @Transactional(readOnly = true)
    public Map<Long, Integer> ranksFor(Long coachId, LocalDate journeyDate) {
        Map<Long, Integer> ranks = new LinkedHashMap<>();
        List<BookingPassenger> queue = queueFor(coachId, journeyDate);
        for (int i = 0; i < queue.size(); i++) {
            ranks.put(queue.get(i).getId(), i + 1);
        }
        return ranks;
    }

    /**
     * Renumbers the queue so the stored position is the real rank, then tells the affected passengers
     * about it.
     * <p>
     * Two messages, and only these two:
     * <ul>
     *   <li>a passenger who was not in {@code ranksBefore} has <b>joined</b> the queue — told their
     *       new place;</li>
     *   <li>a passenger whose rank got <b>better</b> has moved up — told the old and new places.</li>
     * </ul>
     * A passenger whose rank got worse is not notified. Losing places happens to a queue for reasons
     * that are already visible elsewhere (their own booking changed, someone ahead promoted in) and
     * a stream of "you are now 6th, you are now 7th" is noise, not service. Promotion to an actual
     * seat or RAC berth is a different message, sent by
     * {@link SeatAllocationService#promoteQueue}, and is never suppressed by a preference.
     */
    @Transactional
    public void reconcile(Long coachId, LocalDate journeyDate, Map<Long, Integer> ranksBefore) {
        List<BookingPassenger> queue = queueFor(coachId, journeyDate);
        if (queue.isEmpty()) {
            return;
        }
        Map<Long, Integer> ranksAfter = new LinkedHashMap<>();
        List<BookingPassenger> renumbered = new ArrayList<>();
        for (int i = 0; i < queue.size(); i++) {
            BookingPassenger passenger = queue.get(i);
            int rank = i + 1;
            ranksAfter.put(passenger.getId(), rank);
            if (!Integer.valueOf(rank).equals(passenger.getWaitlistPosition())) {
                passenger.setWaitlistPosition(rank);
                renumbered.add(passenger);
            }
        }
        if (!renumbered.isEmpty()) {
            bookingPassengerRepository.saveAll(renumbered);
        }

        Map<Long, Integer> before = ranksBefore == null ? Map.of() : ranksBefore;
        for (BookingPassenger passenger : queue) {
            Integer was = before.get(passenger.getId());
            int now = ranksAfter.get(passenger.getId());
            if (was == null) {
                announceJoined(passenger, now);
            } else if (now < was) {
                announceMoved(passenger, was, now);
            }
        }
    }

    private void announceJoined(BookingPassenger passenger, int rank) {
        Booking booking = passenger.getBooking();
        notificationService.notify(booking.getUser(), NotificationType.WAITLIST_JOINED,
                "Added to the waitlist",
                passenger.getFullName() + " is on the waitlist for " + journey(booking)
                        + ", booking " + booking.getBookingReference() + ". "
                        + (passenger.getPassengerStatus() == PassengerStatus.RAC
                        ? "A seat is not available right now, so an RAC berth has been held: you are number "
                        + rank + " in the queue and a seat is guaranteed once a confirmed booking is cancelled."
                        : "You are number " + rank + " in the queue. We will tell you when your place improves "
                        + "or a seat is offered."));
    }

    private void announceMoved(BookingPassenger passenger, int was, int now) {
        Booking booking = passenger.getBooking();
        notificationService.notify(booking.getUser(), NotificationType.WAITLIST_MOVED,
                "Moved up the waitlist",
                passenger.getFullName() + " has moved from position " + was + " to position " + now
                        + " in the queue for " + journey(booking)
                        + ", booking " + booking.getBookingReference() + ".");
    }

    private static String journey(Booking booking) {
        return booking.getTrain().getName() + " (#" + booking.getTrain().getTrainNumber() + ") on "
                + booking.getJourneyDate();
    }
}
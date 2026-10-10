package com.yatrarail.repository;

import com.yatrarail.entity.BookingPassenger;
import com.yatrarail.entity.PassengerStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface BookingPassengerRepository extends JpaRepository<BookingPassenger, Long> {

    Optional<BookingPassenger> findFirstByBookingIdOrderByIdAsc(Long bookingId);

    /**
     * The promotion queue for one coach + journey date, oldest entry first.
     * <p>
     * {@code waitlistPosition} is the authoritative ordering; {@code id} only breaks ties for
     * legacy rows that pre-date the column. A passenger in their booking's original order is always
     * placed first, which is what {@code bp.id asc} gives us for entries sharing a position.
     */
    @Query("select bp from BookingPassenger bp " +
           "where bp.booking.coach.id = :coachId and bp.booking.journeyDate = :journeyDate " +
           "and bp.passengerStatus in :statuses " +
           "and bp.booking.status in (com.yatrarail.entity.BookingStatus.CONFIRMED, " +
           "                          com.yatrarail.entity.BookingStatus.RAC, " +
           "                          com.yatrarail.entity.BookingStatus.WAITLISTED) " +
           "order by bp.waitlistPosition asc nulls last, bp.id asc")
    List<BookingPassenger> findPromotionQueue(
            @Param("coachId") Long coachId,
            @Param("journeyDate") LocalDate journeyDate,
            @Param("statuses") Collection<PassengerStatus> statuses);

    long countByBookingCoachIdAndBookingJourneyDateAndPassengerStatusInAndBookingStatusIn(
            Long coachId,
            LocalDate journeyDate,
            Collection<PassengerStatus> passengerStatuses,
            Collection<com.yatrarail.entity.BookingStatus> bookingStatuses);

    @Query("select max(bp.waitlistPosition) from BookingPassenger bp " +
           "where bp.booking.coach.id = :coachId and bp.booking.journeyDate = :journeyDate")
    Integer findMaxWaitlistPosition(
            @Param("coachId") Long coachId,
            @Param("journeyDate") LocalDate journeyDate);

    // ------------------------------------------------------------------
    // Phase 6 — gate check-in counts and the boarding-state lookup used by BoardingService.
    // ------------------------------------------------------------------

    /**
     * Boarding tallies for one train + journey date, grouped by state, in a single round trip.
     * Counting in the database rather than in Java keeps the staff dashboard from loading every
     * passenger of every train just to add up four numbers.
     */
    @Query("select bp.boardingStatus, count(bp) from BookingPassenger bp " +
           "where bp.booking.train.id = :trainId and bp.booking.journeyDate = :journeyDate " +
           "and bp.passengerStatus in :passengerStatuses " +
           "and bp.booking.status in (com.yatrarail.entity.BookingStatus.CONFIRMED, " +
           "                          com.yatrarail.entity.BookingStatus.RAC, " +
           "                          com.yatrarail.entity.BookingStatus.WAITLISTED) " +
           "group by bp.boardingStatus")
    List<Object[]> countBoardingByStatusForTrain(
            @Param("trainId") Long trainId,
            @Param("journeyDate") LocalDate journeyDate,
            @Param("passengerStatuses") Collection<PassengerStatus> passengerStatuses);

    /**
     * The same tally across <em>every</em> train on a date, for the staff dashboard.
     * <p>
     * Deliberately a separate query rather than passing a null train id to
     * {@link #countBoardingByStatusForTrain}: {@code train.id = NULL} matches no rows at all, so the
     * shared version would report a permanently empty dashboard rather than failing loudly.
     */
    @Query("select bp.boardingStatus, count(bp) from BookingPassenger bp " +
           "where bp.booking.journeyDate = :journeyDate " +
           "and bp.passengerStatus in :passengerStatuses " +
           "and bp.booking.status in (com.yatrarail.entity.BookingStatus.CONFIRMED, " +
           "                          com.yatrarail.entity.BookingStatus.RAC, " +
           "                          com.yatrarail.entity.BookingStatus.WAITLISTED) " +
           "group by bp.boardingStatus")
    List<Object[]> countBoardingByStatusForDate(
            @Param("journeyDate") LocalDate journeyDate,
            @Param("passengerStatuses") Collection<PassengerStatus> passengerStatuses);
}

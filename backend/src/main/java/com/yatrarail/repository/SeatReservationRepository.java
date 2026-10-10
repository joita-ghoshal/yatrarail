package com.yatrarail.repository;

import com.yatrarail.entity.ReservationStatus;
import com.yatrarail.entity.SeatReservation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SeatReservationRepository extends JpaRepository<SeatReservation, Long> {

    Optional<SeatReservation> findByBookingPassengerId(Long bookingPassengerId);

    /**
     * Seats of a coach that already hold a live reservation on the given journey date. A reservation
     * is "live" when it is CONFIRMED, or HELD with a deadline that has not elapsed yet.
     */
    @Query("select r.seat.id from SeatReservation r " +
           "where r.seat.coach.id = :coachId and r.journeyDate = :journeyDate " +
           "and (r.status = com.yatrarail.entity.ReservationStatus.CONFIRMED " +
           "     or (r.status = com.yatrarail.entity.ReservationStatus.HELD and r.expiresAt > :now))")
    List<Long> findOccupiedSeatIdsForCoach(
            @Param("coachId") Long coachId,
            @Param("journeyDate") LocalDate journeyDate,
            @Param("now") LocalDateTime now);

    /** Same as above, restricted to a specific set of seats (used inside a coach lock). */
    @Query("select r.seat.id from SeatReservation r " +
           "where r.seat.id in :seatIds and r.journeyDate = :journeyDate " +
           "and (r.status = com.yatrarail.entity.ReservationStatus.CONFIRMED " +
           "     or (r.status = com.yatrarail.entity.ReservationStatus.HELD and r.expiresAt > :now))")
    List<Long> findOccupiedSeatIds(
            @Param("seatIds") Collection<Long> seatIds,
            @Param("journeyDate") LocalDate journeyDate,
            @Param("now") LocalDateTime now);

    long countByCoachIdAndJourneyDateAndStatus(
            Long coachId, LocalDate journeyDate, ReservationStatus status);

    long countByCoachIdAndJourneyDateAndStatusIn(
            Long coachId, LocalDate journeyDate, Collection<ReservationStatus> statuses);

    long countByCoachIdAndJourneyDateAndStatusInAndExpiresAtAfter(
            Long coachId, LocalDate journeyDate, Collection<ReservationStatus> statuses, LocalDateTime after);

    @Modifying
    @Query("delete from SeatReservation r where r.bookingPassenger.booking.id = :bookingId")
    int deleteByBookingId(@Param("bookingId") Long bookingId);

    @Modifying
    @Query("delete from SeatReservation r where r.bookingPassenger.id = :bookingPassengerId")
    int deleteByBookingPassengerId(@Param("bookingPassengerId") Long bookingPassengerId);

    List<SeatReservation> findByBookingPassengerBookingId(Long bookingId);

    /** Holds whose payment window has elapsed — the input of the pending-expiry job. */
    List<SeatReservation> findByStatusAndExpiresAtBefore(ReservationStatus status, LocalDateTime cutoff);

    /**
     * Phase 10 — count-only form of the line above, for the readiness probe.
     * <p>
     * A health check must not materialise rows to count them; on a large ledger that turns a
     * cheap probe into the heaviest query the system runs. A sustained non-zero value here means
     * the expiry job has stopped draining holds, which is a real, visible signal.
     */
    long countByStatusAndExpiresAtBefore(ReservationStatus status, LocalDateTime cutoff);

    long countByJourneyDateAndStatus( LocalDate journeyDate, ReservationStatus status);

    /**
     * Phase 4 — how much of each quota's slice of a class's inventory is already committed on one
     * journey date.
     * <p>
     * Counted from this table rather than from the booking rows because a reservation <i>is</i> the
     * claim: a live hold (paid or not yet) occupies the seat and therefore occupies the quota, and
     * deleting the row on cancellation/expiry releases it again. A quota is a per (train, class)
     * allowance spread over every coach of that class, which is why the seat class is resolved
     * through the passenger's booking.
     *
     * @return rows of {@code [QuotaType, count]}; quotas with nothing committed are simply absent.
     */
    @Query("select r.quota, count(r) from SeatReservation r " +
           "where r.trainId = :trainId " +
           "and r.journeyDate = :journeyDate " +
           "and r.bookingPassenger.booking.coach.seatClass = :seatClass " +
           "and (r.status = com.yatrarail.entity.ReservationStatus.CONFIRMED " +
           "     or (r.status = com.yatrarail.entity.ReservationStatus.HELD and r.expiresAt > :now)) " +
           "group by r.quota")
    List<Object[]> countLiveByQuota(
            @Param("trainId") Long trainId,
            @Param("seatClass") com.yatrarail.entity.SeatClass seatClass,
            @Param("journeyDate") LocalDate journeyDate,
            @Param("now") LocalDateTime now);

    /**
     * The same measure, but per journey date across the whole train+class. Used by the admin quota
     * editor to refuse a change that would retroactively break bookings already made.
     *
     * @return rows of {@code [LocalDate, QuotaType, count]}.
     */
    @Query("select r.journeyDate, r.quota, count(r) from SeatReservation r " +
           "where r.trainId = :trainId " +
           "and r.bookingPassenger.booking.coach.seatClass = :seatClass " +
           "and (r.status = com.yatrarail.entity.ReservationStatus.CONFIRMED " +
           "     or (r.status = com.yatrarail.entity.ReservationStatus.HELD and r.expiresAt > :now)) " +
           "group by r.journeyDate, r.quota")
    List<Object[]> countLiveByJourneyDateAndQuota(
            @Param("trainId") Long trainId,
            @Param("seatClass") com.yatrarail.entity.SeatClass seatClass,
            @Param("now") LocalDateTime now);
}

package com.yatrarail.repository;

import com.yatrarail.entity.Booking;
import com.yatrarail.entity.BookingStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Booking queries, including the Phase 7 admin search and the report aggregations.
 * <p>
 * Every optional journey-date filter below is written as
 * {@code journey_date >= coalesce(:journeyFrom, journey_date)} rather than the more familiar
 * {@code (:journeyFrom is null or journey_date >= :journeyFrom)}. The second form binds an untyped
 * NULL to a parameter that appears nowhere else in the statement, and PostgreSQL refuses to guess
 * its type; H2 accepts it, so the failure only shows up against the real database. The
 * {@code coalesce} form borrows the column's type, and with no bound supplied the predicate becomes
 * a column compared with itself, which is true for this NOT NULL column.
 */
public interface BookingRepository extends JpaRepository<Booking, Long> {

    Optional<Booking> findByBookingReference(String bookingReference);

    Optional<Booking> findByIdempotencyKey(String idempotencyKey);

    List<Booking> findByUserIdOrderByCreatedAtDesc(Long userId);

    List<Booking> findAllByOrderByCreatedAtDesc();

    /**
     * Bookings that currently hold inventory (a seat hold or a confirmed/RAC/waitlisted seat) for
     * this train + journey date. Used to prevent a passenger holding two live bookings on the same
     * train and day.
     */
    @Query("select b from Booking b where b.user.id = :userId and b.train.id = :trainId " +
           "and b.journeyDate = :journeyDate and b.status in :statuses")
    List<Booking> findLiveBookingsForUserAndJourney(
            @Param("userId") Long userId,
            @Param("trainId") Long trainId,
            @Param("journeyDate") LocalDate journeyDate,
            @Param("statuses") Collection<BookingStatus> statuses);

    long countByTrainIdAndJourneyDateAndStatus(Long trainId, LocalDate journeyDate, BookingStatus status);

    List<Booking> findByTrainIdAndJourneyDateAndStatusInOrderByCreatedAtDesc(
            Long trainId, LocalDate journeyDate, Collection<BookingStatus> statuses);

    /** Unpaid bookings whose payment window has elapsed — the input of the pending-expiry job. */
    List<Booking> findByStatusInAndPaymentDeadlineBefore(Collection<BookingStatus> statuses, LocalDateTime cutoff);

    @Query("select b from Booking b where b.id = :id")
    Optional<Booking> findByIdForUpdate(@Param("id") Long id);

    @Query("select count(b) from Booking b where b.train.id = :trainId")
    long countByTrainId(@Param("trainId") Long trainId);

    // ------------------------------------------------------------------
    // Phase 6 — date-wide counts for the staff dashboard (no train filter).
    // ------------------------------------------------------------------

    long countByJourneyDateAndStatusIn(LocalDate journeyDate, Collection<BookingStatus> statuses);

    long countByJourneyDateAndStatus(LocalDate journeyDate, BookingStatus status);

    boolean existsByIdAndUserId(Long id, Long userId);

    long countByUserId(Long userId);

    // ------------------------------------------------------------------
    // Phase 7 - paginated, filterable admin booking search.
    // ------------------------------------------------------------------

    /**
     * The admin bookings table.
     * <p>
     * Search matches the reference, the passenger's name, the train name and the train number, since
     * an operator is given any one of those. {@code journeyFrom}/{@code journeyTo} bound the journey
     * date rather than the creation date, because "bookings for this week" means the week of travel.
     */
    @Query("""
            select b from Booking b
            where (:search is null
                   or lower(b.bookingReference) like :search
                   or lower(b.train.name) like :search
                   or lower(b.train.trainNumber) like :search
                   or exists (select p from BookingPassenger p
                              where p.booking = b and lower(p.fullName) like :search))
              and (:status is null or b.status = :status)
              and (:trainId is null or b.train.id = :trainId)
              and (:userId is null or b.user.id = :userId)
              and (b.journeyDate >= coalesce(:journeyFrom, b.journeyDate))
              and (b.journeyDate <= coalesce(:journeyTo, b.journeyDate))
            """)
    org.springframework.data.domain.Page<Booking> search(
            @Param("search") String search,
            @Param("status") BookingStatus status,
            @Param("trainId") Long trainId,
            @Param("userId") Long userId,
            @Param("journeyFrom") LocalDate journeyFrom,
            @Param("journeyTo") LocalDate journeyTo,
            org.springframework.data.domain.Pageable pageable);

    /**
     * Aggregation for the Phase 7 reports. Revenue is only counted for bookings that hold or held
     * inventory, matching how the dashboard already counts it, so the two screens cannot disagree.
     * Grouping is done in SQL rather than by streaming every row into Java, because a report over a
     * year of bookings should not have to load a year of bookings.
     */
    @Query("""
            select b.train.id, b.train.trainNumber, b.train.name, count(b),
                   sum(case when b.status in :revenueStatuses then b.totalFare else 0 end)
            from Booking b
            where (b.journeyDate >= coalesce(:journeyFrom, b.journeyDate))
              and (b.journeyDate <= coalesce(:journeyTo, b.journeyDate))
            group by b.train.id, b.train.trainNumber, b.train.name
            order by count(b) desc
            """)
    List<Object[]> summariseByTrain(
            @Param("journeyFrom") LocalDate journeyFrom,
            @Param("journeyTo") LocalDate journeyTo,
            @Param("revenueStatuses") Collection<BookingStatus> revenueStatuses);

    @Query("""
            select b.journeyDate, count(b),
                   sum(case when b.status in :revenueStatuses then b.totalFare else 0 end),
                   sum(case when b.status = com.yatrarail.entity.BookingStatus.CANCELLED then 1 else 0 end)
            from Booking b
            where (b.journeyDate >= coalesce(:journeyFrom, b.journeyDate))
              and (b.journeyDate <= coalesce(:journeyTo, b.journeyDate))
            group by b.journeyDate
            order by b.journeyDate asc
            """)
    List<Object[]> summariseByJourneyDate(
            @Param("journeyFrom") LocalDate journeyFrom,
            @Param("journeyTo") LocalDate journeyTo,
            @Param("revenueStatuses") Collection<BookingStatus> revenueStatuses);

    @Query("""
            select b.sourceStation.code, b.destinationStation.code, count(b)
            from Booking b
            where (b.journeyDate >= coalesce(:journeyFrom, b.journeyDate))
              and (b.journeyDate <= coalesce(:journeyTo, b.journeyDate))
            group by b.sourceStation.code, b.destinationStation.code
            order by count(b) desc
            """)
    List<Object[]> summariseByRoute(
            @Param("journeyFrom") LocalDate journeyFrom,
            @Param("journeyTo") LocalDate journeyTo);

    @Query("""
            select b.status, count(b)
            from Booking b
            where (b.journeyDate >= coalesce(:journeyFrom, b.journeyDate))
              and (b.journeyDate <= coalesce(:journeyTo, b.journeyDate))
            group by b.status
            """)
    List<Object[]> summariseByStatus(
            @Param("journeyFrom") LocalDate journeyFrom,
            @Param("journeyTo") LocalDate journeyTo);

    /**
     * Headline totals for the Phase 7 report, in one pass.
     * <p>
     * The passenger count is a row count over non-cancelled passengers, not a count of distinct
     * people: a passenger is a row on a booking, and this schema has no identity to deduplicate
     * against. Two travellers who happen to share a name are two passengers, and collapsing them
     * would quietly understate demand on a busy route.
     */
    @Query("""
            select count(b),
                   sum(case when b.status = com.yatrarail.entity.BookingStatus.CONFIRMED then 1 else 0 end),
                   sum(case when b.status = com.yatrarail.entity.BookingStatus.RAC then 1 else 0 end),
                   sum(case when b.status = com.yatrarail.entity.BookingStatus.WAITLISTED then 1 else 0 end),
                   sum(case when b.status = com.yatrarail.entity.BookingStatus.CANCELLED then 1 else 0 end),
                   sum(case when b.status = com.yatrarail.entity.BookingStatus.EXPIRED then 1 else 0 end),
                   sum(case when b.status = com.yatrarail.entity.BookingStatus.PAYMENT_FAILED then 1 else 0 end),
                   sum(case when b.status = com.yatrarail.entity.BookingStatus.PAYMENT_PENDING then 1 else 0 end),
                   coalesce(sum(case when b.status in :revenueStatuses then b.totalFare else 0 end), 0),
                   coalesce(sum(case when b.status in :revenueStatuses then b.platformFee else 0 end), 0)
            from Booking b
            where (b.journeyDate >= coalesce(:journeyFrom, b.journeyDate))
              and (b.journeyDate <= coalesce(:journeyTo, b.journeyDate))
            """)
    List<Object[]> summariseTotals(
            @Param("journeyFrom") LocalDate journeyFrom,
            @Param("journeyTo") LocalDate journeyTo,
            @Param("revenueStatuses") Collection<BookingStatus> revenueStatuses);

    /** Non-cancelled passenger rows over the range: the "how many people are travelling" figure. */
    @Query("""
            select count(p) from BookingPassenger p
            where p.passengerStatus <> com.yatrarail.entity.PassengerStatus.CANCELLED
              and (p.booking.journeyDate >= coalesce(:journeyFrom, p.booking.journeyDate))
              and (p.booking.journeyDate <= coalesce(:journeyTo, p.booking.journeyDate))
            """)
    long countPassengers(
            @Param("journeyFrom") LocalDate journeyFrom,
            @Param("journeyTo") LocalDate journeyTo);
}

package com.yatrarail.repository;

import com.yatrarail.entity.Coach;
import com.yatrarail.entity.SeatClass;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CoachRepository extends JpaRepository<Coach, Long> {
    List<Coach> findByTrainId(Long trainId);
    List<Coach> findByTrainIdAndActiveTrue(Long trainId);
    void deleteByTrainId(Long trainId);

    Optional<Coach> findByTrainIdAndSeatClass(Long trainId, SeatClass seatClass);

    /** Coach codes are unique per train, not globally, so "A1" can exist on every service. */
    boolean existsByTrainIdAndCoachCodeIgnoreCase(Long trainId, String coachCode);

    /**
     * Selects the coach row {@code FOR UPDATE}. Taking this lock before allocating seats serialises
     * every concurrent allocation for the same coach, so two bookings can never read the same free
     * seat. The {@code (seat_id, journey_date)} unique constraint on {@code seat_reservations} is
     * still the final backstop if this lock is ever removed.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Coach c where c.id = :id")
    Optional<Coach> findByIdForUpdate(@Param("id") Long id);

    /**
     * Physical seats a train actually owns in one class, summed over its active coaches.
     * <p>
     * This is the number the quota split is carved out of: a quota is a per-(train, class)
     * allowance, so it must be measured against the whole class rather than against one coach.
     * Coached out of inventory are excluded, so closing a coach immediately widens the general
     * quota instead of silently stranding reserved seats.
     */
    @Query("select coalesce(sum(c.totalSeats), 0) from Coach c " +
           "where c.train.id = :trainId and c.seatClass = :seatClass and c.active = true")
    long sumTotalSeatsByTrainAndClass(@Param("trainId") Long trainId,
                                      @Param("seatClass") SeatClass seatClass);

    /**
     * Phase 4 — {@code FOR UPDATE} on <i>all</i> active coaches of one class, in id order.
     * <p>
     * A booking quota is a per-(train, class) allowance, so two passengers booking two different
     * coaches of the same class compete for the same slice. Locking the whole class (rather than
     * just the booked coach) is what makes the quota budget trustworthy. The {@code order by c.id}
     * fixes the order rows are locked in, so two transactions taking overlapping class locks cannot
     * deadlock.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Coach c where c.train.id = :trainId and c.seatClass = :seatClass " +
           "and c.active = true order by c.id")
    List<Coach> findByTrainAndClassForUpdate(@Param("trainId") Long trainId,
                                            @Param("seatClass") SeatClass seatClass);
}

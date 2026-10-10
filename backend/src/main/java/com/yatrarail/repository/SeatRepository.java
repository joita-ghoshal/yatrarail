package com.yatrarail.repository;

import com.yatrarail.entity.Seat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SeatRepository extends JpaRepository<Seat, Long> {
    List<Seat> findByCoachIdAndActiveTrueOrderBySeatNumberAsc(Long coachId);
    List<Seat> findByCoachIdOrderBySeatNumberAsc(Long coachId);
    void deleteByCoachId(Long coachId);

    long countByCoachId(Long coachId);

    /**
     * Highest seat number currently present in a coach, used to stop an admin shrinking a coach
     * below the inventory that already exists.
     */
    @Query("select coalesce(max(s.seatNumber), 0) from Seat s where s.coach.id = :coachId")
    int findMaxSeatNumberByCoachId(@Param("coachId") Long coachId);
}

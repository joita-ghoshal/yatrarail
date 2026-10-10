package com.yatrarail.repository;

import com.yatrarail.entity.AssistanceRequest;
import com.yatrarail.entity.AssistanceStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

public interface AssistanceRequestRepository extends JpaRepository<AssistanceRequest, Long> {

    List<AssistanceRequest> findByStatusInOrderByRaisedAtAsc(Collection<AssistanceStatus> statuses);

    /** Scoped to a journey date so the staff queue shows today's work, not all history. */
    List<AssistanceRequest> findByBookingJourneyDateOrderByRaisedAtAsc(LocalDate journeyDate);

    List<AssistanceRequest> findByBookingIdOrderByRaisedAtAsc(Long bookingId);

    /** Prevents the same passenger asking for the same help twice on the same journey. */
    boolean existsByBookingPassengerIdAndCategoryAndStatusIn(
            Long bookingPassengerId, com.yatrarail.entity.AssistanceCategory category,
            Collection<AssistanceStatus> statuses);

    long countByStatusIn(Collection<AssistanceStatus> statuses);

    /** Same as {@link #countByStatusIn} but narrowed to one journey date, for the daily dashboard. */
    long countByBookingJourneyDateAndStatusIn(LocalDate journeyDate, Collection<AssistanceStatus> statuses);
}
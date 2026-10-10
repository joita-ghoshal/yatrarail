package com.yatrarail.repository;

import com.yatrarail.entity.IncidentStatus;
import com.yatrarail.entity.TrainIncident;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

public interface TrainIncidentRepository extends JpaRepository<TrainIncident, Long> {

    List<TrainIncident> findByTrainIdAndJourneyDateOrderByReportedAtDesc(Long trainId, LocalDate journeyDate);

    /** Newest first, optionally narrowed to the ones still needing somebody's attention. */
    List<TrainIncident> findByStatusInOrderByReportedAtDesc(Collection<IncidentStatus> statuses);

    List<TrainIncident> findByJourneyDateOrderByReportedAtDesc(LocalDate journeyDate);

    long countByJourneyDateAndStatusIn(LocalDate journeyDate, Collection<IncidentStatus> statuses);

    long countByStatusIn(Collection<IncidentStatus> statuses);
}
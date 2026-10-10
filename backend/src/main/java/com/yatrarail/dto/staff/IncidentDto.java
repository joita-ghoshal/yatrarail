package com.yatrarail.dto.staff;

import com.yatrarail.entity.TrainIncident;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Incident DTO for the staff incident log. The reporting staff member's name is included (not just
 * an id) because "who reported this" is the first question anybody asks about a disruption, and
 * resolving it to a display name on the frontend would mean shipping the whole user list to the
 * browser.
 */
@Data
@Builder
public class IncidentDto {
    private Long id;
    private Long trainId;
    private String trainName;
    private String trainNumber;
    private LocalDate journeyDate;
    private String type;
    private String severity;
    private String status;
    private Integer delayMinutes;
    private String description;
    private String reportedByName;
    private String resolvedByName;
    private LocalDateTime resolvedAt;
    private LocalDateTime reportedAt;

    public static IncidentDto from(TrainIncident incident) {
        return IncidentDto.builder()
                .id(incident.getId())
                .trainId(incident.getTrain() != null ? incident.getTrain().getId() : null)
                .trainName(incident.getTrain() != null ? incident.getTrain().getName() : null)
                .trainNumber(incident.getTrain() != null ? incident.getTrain().getTrainNumber() : null)
                .journeyDate(incident.getJourneyDate())
                .type(incident.getType().name())
                .severity(incident.getSeverity().name())
                .status(incident.getStatus().name())
                .delayMinutes(incident.getDelayMinutes())
                .description(incident.getDescription())
                .reportedByName(incident.getReportedBy() != null ? incident.getReportedBy().getFullName() : null)
                .resolvedByName(incident.getResolvedBy() != null ? incident.getResolvedBy().getFullName() : null)
                .resolvedAt(incident.getResolvedAt())
                .reportedAt(incident.getReportedAt())
                .build();
    }
}
package com.yatrarail.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A delay or disruption reported by staff against one train on one journey date.
 * <p>
 * Incidents are operational records, not announcements: they are scoped to a train + date so two
 * days of the same service do not contaminate each other's dashboard counts, and they carry the
 * reporting staff member so "who told us" stays answerable.
 */
@Entity
@Table(name = "train_incidents", indexes = {
        @Index(name = "idx_incident_train_date", columnList = "train_id, journey_date"),
        @Index(name = "idx_incident_status", columnList = "status"),
        @Index(name = "idx_incident_reported", columnList = "reported_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TrainIncident {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "train_id", nullable = false)
    private Train train;

    @Column(name = "journey_date", nullable = false)
    private LocalDate journeyDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private IncidentType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 20)
    private IncidentSeverity severity;

    /** {@code @ColumnDefault} as well as the field initialiser, so a fresh database really defaults to OPEN. */
    @Builder.Default
    @ColumnDefault("'OPEN'")
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private IncidentStatus status = IncidentStatus.OPEN;

    /** Minutes of delay. Null for incidents with no time cost, such as a diversion notice. */
    @Column(name = "delay_minutes")
    private Integer delayMinutes;

    @Column(nullable = false, length = 300)
    private String description;

    /** Staff member who filed the report. Never null: only staff can report. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reported_by_id", nullable = false)
    private User reportedBy;

    /** Set when staff acknowledge or resolve, so the handover trail is visible. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resolved_by_id")
    private User resolvedBy;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(name = "reported_at", nullable = false, updatable = false)
    private LocalDateTime reportedAt;

    @PrePersist
    protected void onCreate() {
        reportedAt = LocalDateTime.now();
        if (status == null) {
            status = IncidentStatus.OPEN;
        }
    }
}
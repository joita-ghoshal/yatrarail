package com.yatrarail.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;

import java.time.LocalDateTime;

/**
 * A help request raised by a passenger for one passenger of one booking, and worked by staff.
 * <p>
 * The request is pinned to a {@link BookingPassenger} rather than a booking so a group of three
 * asking for a wheelchair for one of them does not create three identical requests, and so staff
 * see exactly which seat to walk to.
 */
@Entity
@Table(name = "assistance_requests", indexes = {
        @Index(name = "idx_assist_status", columnList = "status"),
        @Index(name = "idx_assist_booking", columnList = "booking_id"),
        @Index(name = "idx_assist_raised", columnList = "raised_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AssistanceRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "booking_passenger_id", nullable = false)
    private BookingPassenger bookingPassenger;

    /** Denormalised so the staff queue can filter by booking without joining through the passenger. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "booking_id", nullable = false)
    private Booking booking;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private AssistanceCategory category;

    /** {@code @ColumnDefault} as well as the field initialiser, so a fresh database really defaults to OPEN. */
    @Builder.Default
    @ColumnDefault("'OPEN'")
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AssistanceStatus status = AssistanceStatus.OPEN;

    @Column(length = 400)
    private String details;

    /** Staff member currently handling it; null while it sits in the queue. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_to_id")
    private User assignedTo;

    @Column(name = "raised_at", nullable = false, updatable = false)
    private LocalDateTime raisedAt;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @PrePersist
    protected void onCreate() {
        raisedAt = LocalDateTime.now();
        if (status == null) {
            status = AssistanceStatus.OPEN;
        }
    }
}
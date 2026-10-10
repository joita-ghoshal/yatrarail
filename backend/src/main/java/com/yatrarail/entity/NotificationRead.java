package com.yatrarail.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * One person's acknowledgement of one broadcast announcement.
 * <p>
 * This table exists because of a concrete defect. An announcement is stored as a <b>single</b> row
 * with a null {@code user_id}, which is the right model for authored content: there is one notice,
 * it is the thing the audit trail points at, and "how many announcements exist" is a row count. But
 * it made the read flag global. {@code notifications.is_read} was a single column on that shared
 * row, so the first passenger to open the notice flipped it for everybody, and after that the notice
 * looked read to everyone who had never seen it. The staff notifier had already worked around the
 * same problem by fanning out one row per recipient, with a comment saying the read state has to be
 * per person; an announcement had no such option.
 * <p>
 * So the two cases are modelled as the two different things they are. A <b>personal</b>
 * notification is <i>delivered</i> to exactly one person and keeps its read state on the row. An
 * <b>announcement</b> is <i>broadcast</i> and is not delivered to anyone, so its read state is a
 * receipt recorded here. Personal notifications deliberately have no receipt: one mechanism per
 * meaning, rather than two mechanisms that can disagree about the same row.
 * <p>
 * The unique constraint on {@code (notification_id, user_id)} is what makes "mark as read" safe to
 * retry — reading the same notice twice records one receipt, not two.
 */
@Entity
@Table(name = "notification_reads",
        uniqueConstraints = @UniqueConstraint(name = "uk_notification_read", columnNames = {"notification_id", "user_id"}),
        indexes = {
                @Index(name = "idx_reads_user", columnList = "user_id, notification_id")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotificationRead {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The announcement that was read. Always a row with a null {@code user}. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "notification_id", nullable = false)
    private Notification notification;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "read_at", nullable = false, updatable = false)
    private LocalDateTime readAt;

    @PrePersist
    protected void onCreate() {
        if (readAt == null) {
            readAt = LocalDateTime.now();
        }
    }
}
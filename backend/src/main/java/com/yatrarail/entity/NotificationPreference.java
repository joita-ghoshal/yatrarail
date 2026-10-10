package com.yatrarail.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * One person's standing instruction about what this platform may tell them.
 * <p>
 * Two fields, both deliberately narrow.
 * <p>
 * <b>{@code announcementsEnabled}</b> is the only genuinely optional channel in the system. Everything
 * else the notifier sends is the receipt of something the recipient did — their seat was held, their
 * payment failed, their queue place moved, a passenger on their booking needs help at the platform —
 * and {@link NotificationType#isSuppressible()} is what keeps a preference from becoming a way to lose
 * a ticket. A user can therefore decline announcements without being able to decline the fact that
 * their own booking expired.
 * <p>
 * <b>{@code mutedUntil}</b> is a snooze rather than a switch. It is a deadline, not a boolean, so it
 * expires on its own: a user who silences the app for a long train ride does not come back to a
 * permanently silent inbox and have to remember they muted it. Like the announcement toggle it
 * applies only to suppressible types, so it silences the platform's broadcasts and never a booking
 * receipt. A cleared preference row is therefore recoverable by construction, and one is only ever
 * written when the user actually changes something.
 */
@Entity
@Table(name = "notification_preferences",
        uniqueConstraints = @UniqueConstraint(name = "uk_notification_pref_user", columnNames = "user_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotificationPreference {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** System-wide announcements are the only advisory notifications, so this is the only opt-out. */
    @Column(name = "announcements_enabled", nullable = false)
    @Builder.Default
    private Boolean announcementsEnabled = true;

    /** How much of the waitlist's interior the recipient wants to hear about. */
    @Enumerated(EnumType.STRING)
    @Column(name = "waitlist_alerts", nullable = false, length = 20)
    @Builder.Default
    private WaitlistAlertMode waitlistAlerts = WaitlistAlertMode.ALL;

    /** Snooze deadline for suppressible notifications; {@code null} means not snoozed. */
    @Column(name = "muted_until")
    private LocalDateTime mutedUntil;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    protected void touch() {
        updatedAt = LocalDateTime.now();
    }

    /** True while the snooze deadline is still in the future. */
    public boolean isMuted(LocalDateTime now) {
        return mutedUntil != null && mutedUntil.isAfter(now);
    }
}
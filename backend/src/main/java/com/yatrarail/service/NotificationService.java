package com.yatrarail.service;

import com.yatrarail.dto.notification.AnnouncementSummaryDto;
import com.yatrarail.dto.notification.NotificationDto;
import com.yatrarail.entity.Notification;
import com.yatrarail.entity.NotificationRead;
import com.yatrarail.entity.NotificationType;
import com.yatrarail.entity.RoleName;
import com.yatrarail.entity.User;
import com.yatrarail.exception.ResourceNotFoundException;
import com.yatrarail.observability.DomainEventPublisher;
import com.yatrarail.observability.PlatformEvent;
import com.yatrarail.repository.NotificationReadRepository;
import com.yatrarail.repository.NotificationRepository;
import com.yatrarail.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Writes and reads the notification feed.
 * <p>
 * <b>Two kinds of notification, two honest read states.</b> A <i>personal</i> notification is
 * delivered to one person and carries its own {@code is_read} flag. An <i>announcement</i> is a
 * single authored row with no recipient, so it carries none: its read state is a per-person receipt
 * in {@code notification_reads}. Before this split existed, {@code is_read} lived on the shared
 * announcement row, so the first person to open a notice marked it read for everybody and the badge
 * never counted announcements at all.
 * <p>
 * <b>Suppression happens before the write, not after.</b> {@link #notify} asks
 * {@link NotificationPreferenceService} whether this person should be told, and writes nothing if not.
 * Doing it here rather than in the UI means a muted passenger is genuinely not written to, and — the
 * reason this is worth being careful about — that a non-suppressible type cannot be suppressed at
 * all: see {@link NotificationType#isSuppressible()}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final NotificationReadRepository notificationReadRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final NotificationPreferenceService preferenceService;
    /** Phase 10: an announcement is a platform-wide fact worth counting and, later, fanning out. */
    private final DomainEventPublisher domainEventPublisher;

    /**
     * Delivers one personal notification, unless the recipient has opted out of this type.
     *
     * @return the stored row, or empty when the recipient's settings say not to tell them
     */
    @Transactional
    public Optional<Notification> notify(User user, NotificationType type, String title, String message) {
        if (user == null || user.getId() == null) {
            return Optional.empty();
        }
        if (!preferenceService.allows(user.getId(), type)) {
            log.debug("Suppressed {} notification for user {} by their preferences", type, user.getId());
            return Optional.empty();
        }
        return Optional.of(notificationRepository.save(Notification.builder()
                .user(user)
                .type(type.name())
                .title(title)
                .message(message)
                .isRead(false)
                .build()));
    }

    // ------------------------------------------------------------------ announcements

    @Transactional
    public NotificationDto announce(String title, String message) {
        return announce(title, message, null);
    }

    /**
     * Publishes one system-wide notice.
     * <p>
     * Stored as a single row with no recipient on purpose: it is authored content, it is what the
     * audit entry points at, and "how many notices exist" should be a row count rather than a
     * multiplication by the size of the user table. Per-reader acknowledgement lives in
     * {@code notification_reads}.
     */
    @Transactional
    public NotificationDto announce(String title, String message, Long actorId) {
        Notification saved = notificationRepository.save(Notification.builder()
                .user(null)
                .type(NotificationType.SYSTEM_ANNOUNCEMENT.name())
                .title(title)
                .message(message)
                // Meaningless on a broadcast row, and asserted as such by the unread query, which
                // tests for a receipt rather than reading this column. Written true so that any
                // future query which does read it cannot mistake "nobody has read it" for "it is
                // not a real notification".
                .isRead(true)
                .build());
        auditService.record(AuditService.ANNOUNCEMENT_SENT, "ANNOUNCEMENT", saved.getId(),
                actor(actorId) + " published the announcement \"" + title + "\"", actorId);
        domainEventPublisher.publish(PlatformEvent.of("ANNOUNCEMENT_PUBLISHED", String.valueOf(saved.getId())));
        return NotificationDto.from(saved, true, false);
    }

    /** "Priya Sharma (ADMIN)" — the prefix every audit summary starts with. */
    private String actor(Long actorId) {
        if (actorId == null) {
            return "System";
        }
        return userRepository.findById(actorId)
                .map(u -> u.getFullName() + " (" + u.getRole().name() + ")")
                .orElse("System");
    }

    /**
     * Every announcement with its reach, newest first, for the admin console.
     * <p>
     * Read counts are grouped in SQL rather than counted per row: an operator with a year of notices
     * would otherwise trigger one count query per notice to render one table.
     */
    @Transactional(readOnly = true)
    public List<AnnouncementSummaryDto> announcementsWithReach() {
        List<Notification> announcements = notificationRepository.findByUserIsNullOrderByCreatedAtDesc();
        if (announcements.isEmpty()) {
            return List.of();
        }
        List<Long> ids = announcements.stream().map(Notification::getId).toList();
        long audience = userRepository.countByActiveTrueAndTemporaryAccountFalse();

        // Count receipts per announcement in one round trip, then assemble.
        Map<Long, Long> counts = notificationReadRepository.findByNotificationIdIn(ids).stream()
                .collect(Collectors.groupingBy(r -> r.getNotification().getId(), Collectors.counting()));

        return announcements.stream()
                .map(n -> AnnouncementSummaryDto.builder()
                        .id(n.getId())
                        .title(n.getTitle())
                        .message(n.getMessage())
                        .createdAt(n.getCreatedAt())
                        .audience(audience)
                        // A receipt can only exist for an account that existed when it was written,
                        // so this cannot exceed the audience; clamped anyway rather than reporting a
                        // percentage above 100% if an account is later purged.
                        .readCount(Math.min(audience, counts.getOrDefault(n.getId(), 0L)))
                        .build())
                .toList();
    }

    // ------------------------------------------------------------------ staff broadcast

    /**
     * Broadcasts an operational notice to every staff member's personal notification list.
     * <p>
     * Written as one row per recipient rather than a shared {@code user_id IS NULL} announcement on
     * purpose: the read state has to be per person. A duty manager who has read the delay notice
     * must not suppress it for the conductor who has not. This is the mirror image of an
     * announcement, and the difference is exactly that — an announcement is broadcast content nobody
     * individually triggered, so a receipt models it correctly, while a staff alert is dispatched to
     * a named person who owns it.
     *
     * @return how many staff were actually notified, after their own settings were applied
     */
    @Transactional
    public int notifyStaff(NotificationType type, String title, String message) {
        List<User> staff = userRepository.findByRoleOrderByIdAsc(RoleName.STAFF);
        if (staff.isEmpty()) {
            return 0;
        }
        List<Notification> notifications = staff.stream()
                .filter(user -> preferenceService.allows(user.getId(), type))
                .map(user -> Notification.builder()
                        .user(user)
                        .type(type.name())
                        .title(title)
                        .message(message)
                        .isRead(false)
                        .build())
                .toList();
        if (notifications.isEmpty()) {
            return 0;
        }
        notificationRepository.saveAll(notifications);
        return notifications.size();
    }

    // ------------------------------------------------------------------ reads

    /**
     * The caller's feed: their own notifications plus every announcement, newest first.
     * <p>
     * Read state is resolved with one receipt query for the whole page rather than one per
     * announcement. This is the most frequently polled endpoint in the application — the navbar bell
     * refreshes on a timer for every signed-in user — so an N+1 here is an N+1 multiplied by the
     * number of notices ever published.
     * <p>
     * Announcements the reader has opted out of are filtered out <i>here as well as</i> at the write.
     * Gating only the write would leave every notice ever published sitting in the feed and in the
     * badge forever, so "off" would have meant "no longer notified, but still interrupted about all
     * the old ones" — which is not off, and is not what the user believes they asked for.
     */
    @Transactional(readOnly = true)
    public List<NotificationDto> getForUser(Long userId) {
        List<Notification> personal = notificationRepository.findByUserIdOrderByCreatedAtDesc(userId);
        if (!preferenceService.allows(userId, NotificationType.SYSTEM_ANNOUNCEMENT)) {
            return personal.stream().map(n -> NotificationDto.from(n, false, Boolean.TRUE.equals(n.getIsRead()))).toList();
        }
        List<Notification> announcements = notificationRepository.findByUserIsNullOrderByCreatedAtDesc();
        Set<Long> readAnnouncements = new HashSet<>(notificationReadRepository.findAnnouncementIdsReadBy(userId));

        List<Notification> combined = new ArrayList<>(personal.size() + announcements.size());
        combined.addAll(personal);
        combined.addAll(announcements);
        combined.sort(Comparator.comparing(Notification::getCreatedAt).reversed());

        return combined.stream()
                .map(n -> {
                    boolean isAnnouncement = n.getUser() == null;
                    boolean read = isAnnouncement
                            ? readAnnouncements.contains(n.getId())
                            : Boolean.TRUE.equals(n.getIsRead());
                    return NotificationDto.from(n, isAnnouncement, read);
                })
                .toList();
    }

    /**
     * Unread personal notifications plus announcements with no receipt from this user.
     * <p>
     * Announcements used to be invisible to this count entirely, so a freshly published service
     * notice produced no badge anywhere — which is the one thing a passenger most needs interrupting
     * for.
     */
    @Transactional(readOnly = true)
    public long unreadCount(Long userId) {
        long personal = notificationRepository.countByUserIdAndIsReadFalse(userId);
        if (!preferenceService.allows(userId, NotificationType.SYSTEM_ANNOUNCEMENT)) {
            return personal;
        }
        return personal + notificationRepository.countUnreadAnnouncementsFor(userId);
    }

    /**
     * Marks one notification read.
     * <p>
     * Ownership: a personal notification must belong to the caller, or the answer is 404 rather than
     * 403 so the endpoint never confirms that somebody else's notification exists. An announcement
     * has no owner by construction, so anyone may acknowledge it — but the acknowledgement is
     * recorded against <i>them</i>, which is the entire point.
     * <p>
     * Idempotent: acknowledging the same announcement twice records one receipt.
     */
    @Transactional
    public NotificationDto markAsRead(Long notificationId, Long userId) {
        Notification notification = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new ResourceNotFoundException("Notification not found"));
        if (notification.getUser() != null) {
            if (!notification.getUser().getId().equals(userId)) {
                throw new ResourceNotFoundException("Notification not found");
            }
            notification.setIsRead(true);
            return NotificationDto.from(notificationRepository.save(notification), false, true);
        }
        recordRead(notification, userId);
        return NotificationDto.from(notification, true, true);
    }

    /**
     * Marks the caller's whole inbox read: unread personal notifications in one statement, and a
     * receipt against every announcement they have not acknowledged.
     * <p>
     * The previous implementation only handled personal rows, so "Mark all read" left every
     * announcement looking unread forever — the badge came back the moment the page reloaded.
     * <p>
     * Hidden announcements are left alone rather than acknowledged. Writing receipts for notices the
     * reader cannot see would burn the one signal that tells an administrator whether anybody read
     * them, and would silently mark them read for a reader who never was.
     */
    @Transactional
    public void markAllAsRead(Long userId) {
        notificationRepository.markAllPersonalAsRead(userId);

        if (!preferenceService.allows(userId, NotificationType.SYSTEM_ANNOUNCEMENT)) {
            return;
        }
        List<Notification> unread = notificationRepository.findUnreadAnnouncementsFor(userId);
        if (unread.isEmpty()) {
            return;
        }
        List<NotificationRead> receipts = unread.stream()
                .map(notification -> NotificationRead.builder()
                        .notification(notification)
                        .user(userRepository.getReferenceById(userId))
                        .readAt(LocalDateTime.now())
                        .build())
                .toList();
        notificationReadRepository.saveAll(receipts);
    }

    private void recordRead(Notification notification, Long userId) {
        if (notificationReadRepository.existsByNotificationIdAndUserId(notification.getId(), userId)) {
            return;
        }
        notificationReadRepository.save(NotificationRead.builder()
                .notification(notification)
                .user(userRepository.getReferenceById(userId))
                .readAt(LocalDateTime.now())
                .build());
    }
}
package com.yatrarail.service;

import com.yatrarail.dto.notification.NotificationPreferenceDto;
import com.yatrarail.dto.notification.UpdateNotificationPreferenceRequest;
import com.yatrarail.entity.NotificationPreference;
import com.yatrarail.entity.NotificationType;
import com.yatrarail.entity.User;
import com.yatrarail.entity.WaitlistAlertMode;
import com.yatrarail.exception.BadRequestException;
import com.yatrarail.exception.ResourceNotFoundException;
import com.yatrarail.repository.NotificationPreferenceRepository;
import com.yatrarail.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Reads and writes a user's notification settings, and answers the one question the notifier needs:
 * may this person be told this?
 * <p>
 * <b>An absent row means the defaults, not an empty preference.</b> Reading settings must not have a
 * write side effect, so a user who has never touched them is served a default object and no row is
 * created; one is written the first time they actually change something. The consequence is that
 * "delete your preferences" and "I never had any" are the same state, which is correct — both mean
 * the platform's defaults apply.
 * <p>
 * <b>The gate is enforced on the write path, not in the UI.</b> {@link #allows} is consulted by
 * {@link NotificationService} before a row is created, so a crafted request cannot make the platform
 * deliver something the recipient muted, and equally a muted passenger cannot be used to suppress
 * somebody else's receipt.
 */
@Service
@RequiredArgsConstructor
public class NotificationPreferenceService {

    private final NotificationPreferenceRepository preferenceRepository;
    private final UserRepository userRepository;

    /**
     * The default settings, as a detached object. Never persisted on a read.
     */
    private static NotificationPreference defaults() {
        return NotificationPreference.builder()
                .announcementsEnabled(true)
                .waitlistAlerts(WaitlistAlertMode.ALL)
                .mutedUntil(null)
                .build();
    }

    @Transactional(readOnly = true)
    public NotificationPreferenceDto get(Long userId) {
        NotificationPreference preference = preferenceRepository.findByUserId(userId).orElse(null);
        return toDto(preference == null ? defaults() : preference);
    }

    @Transactional
    public NotificationPreferenceDto update(Long userId, UpdateNotificationPreferenceRequest request) {
        WaitlistAlertMode mode;
        try {
            mode = WaitlistAlertMode.valueOf(request.getWaitlistAlerts());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BadRequestException("waitlistAlerts must be one of ALL, PROMOTED_ONLY or OFF");
        }
        if (request.getMutedUntil() != null && !request.getMutedUntil().isAfter(LocalDateTime.now())) {
            // A snooze that has already ended is a setting nobody can act on. Refusing it is clearer
            // than silently storing a mute that reports "not muted" the instant it is saved.
            throw new BadRequestException("mutedUntil must be a time in the future, or omitted");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found"));
        NotificationPreference preference = preferenceRepository.findByUserId(userId)
                .orElseGet(() -> NotificationPreference.builder().user(user).build());
        preference.setAnnouncementsEnabled(Boolean.TRUE.equals(request.getAnnouncementsEnabled()));
        preference.setWaitlistAlerts(mode);
        preference.setMutedUntil(request.getMutedUntil());
        return toDto(preferenceRepository.save(preference));
    }

    /**
     * Whether this recipient should be told about this type right now.
     * <p>
     * Non-suppressible types always return {@code true}. That single line is the whole reason the
     * rest of this class is allowed to exist: no combination of settings can hide a payment failure,
     * an expiry, a cancellation, a promotion off the waitlist, or a staff alert that somebody has to
     * act on.
     */
    @Transactional(readOnly = true)
    public boolean allows(Long userId, NotificationType type) {
        if (!type.isSuppressible()) {
            return true;
        }
        NotificationPreference preference = preferenceRepository.findByUserId(userId).orElse(null);
        return allows(preference, type, LocalDateTime.now());
    }

    /** The same decision, for an already-loaded preference. Split out so the rules are testable without a user. */
    boolean allows(NotificationPreference preference, NotificationType type, LocalDateTime now) {
        if (!type.isSuppressible()) {
            return true;
        }
        boolean muted = preference != null && preference.isMuted(now);
        WaitlistAlertMode mode = preference == null ? WaitlistAlertMode.ALL : preference.getWaitlistAlerts();
        return switch (type) {
            case SYSTEM_ANNOUNCEMENT ->
                    !muted && (preference == null || !Boolean.FALSE.equals(preference.getAnnouncementsEnabled()));
            // "You are on the list, you are number 4" is the opening of the conversation; a passenger
            // who asked for position changes only does not want it. "You have moved to 2" is the
            // part they asked to hear.
            case WAITLIST_JOINED -> !muted && mode == WaitlistAlertMode.ALL;
            case WAITLIST_MOVED -> !muted && mode != WaitlistAlertMode.OFF;
            default -> true;
        };
    }

    private NotificationPreferenceDto toDto(NotificationPreference preference) {
        List<NotificationPreferenceDto.WaitlistOption> options = Arrays.stream(WaitlistAlertMode.values())
                .map(m -> NotificationPreferenceDto.WaitlistOption.builder()
                        .value(m.name())
                        .label(m.getLabel())
                        .build())
                .toList();

        // Derived from the enum rather than hand-listed, so a new always-on type cannot be added
        // without this screen starting to tell the truth about it.
        Set<String> lockedCategories = new LinkedHashSet<>();
        for (NotificationType type : NotificationType.values()) {
            if (!type.isSuppressible()) {
                lockedCategories.add(type.getCategoryLabel());
            }
        }

        return NotificationPreferenceDto.builder()
                .announcementsEnabled(preference == null || !Boolean.FALSE.equals(preference.getAnnouncementsEnabled()))
                .waitlistAlerts((preference == null ? WaitlistAlertMode.ALL : preference.getWaitlistAlerts()).name())
                .waitlistOptions(options)
                .mutedUntil(preference == null ? null : preference.getMutedUntil())
                .muted(preference != null && preference.isMuted(LocalDateTime.now()))
                .alwaysDelivered(lockedCategories.stream()
                        .map(category -> NotificationPreferenceDto.AlwaysDelivered.builder()
                                .category(category)
                                .reason("This is the record of something you did. It is always delivered, "
                                        + "so you never find out about it late.")
                                .build())
                        .toList())
                .build();
    }
}
package com.yatrarail.dto.notification;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * The only three things a user may change about how they are notified.
 * <p>
 * There is deliberately no "mute everything" flag. {@code mutedUntil} is a deadline instead of a
 * switch, and it silences only the suppressible types — see
 * {@link com.yatrarail.entity.NotificationType#isSuppressible()}. A setting that could hide "your
 * payment failed" is not a preference, it is a way to lose a ticket.
 */
@Data
public class UpdateNotificationPreferenceRequest {

    @NotNull(message = "announcementsEnabled is required")
    private Boolean announcementsEnabled;

    /**
     * One of ALL / PROMOTED_ONLY / OFF. Validated against the enum's own names rather than a
     * hand-written list, so adding a mode cannot leave this rejecting a value the server accepts.
     */
    @NotNull(message = "waitlistAlerts is required")
    @Pattern(regexp = "ALL|PROMOTED_ONLY|OFF",
            message = "waitlistAlerts must be one of ALL, PROMOTED_ONLY or OFF")
    private String waitlistAlerts;

    /** Optional snooze deadline. Must be in the future if present. */
    private LocalDateTime mutedUntil;
}
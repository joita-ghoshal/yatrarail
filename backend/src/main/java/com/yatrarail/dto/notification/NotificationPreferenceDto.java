package com.yatrarail.dto.notification;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * A user's notification settings, together with an honest statement of what they cannot change.
 * <p>
 * The {@code alwaysDelivered} list is the point of this DTO. A settings screen that only shows
 * toggles invites the reader to assume every channel is optional; sending that list means the client
 * can say <i>why</i> a booking receipt has no switch, instead of the rule looking like an oversight.
 */
@Data
@Builder
public class NotificationPreferenceDto {

    private Boolean announcementsEnabled;
    private String waitlistAlerts;

    /** Human labels for {@link #waitlistAlerts}, so the client does not re-spell the enum. */
    private List<WaitlistOption> waitlistOptions;

    /** Snooze deadline for suppressible notifications; {@code null} when not snoozed. */
    private LocalDateTime mutedUntil;

    /** True while {@link #mutedUntil} is still in the future. */
    private Boolean muted;

    /** Preference groups whose notifications are always delivered, with the reason. */
    private List<AlwaysDelivered> alwaysDelivered;

    @Data
    @Builder
    public static class WaitlistOption {
        private String value;
        private String label;
    }

    @Data
    @Builder
    public static class AlwaysDelivered {
        private String category;

        /** Why this cannot be switched off, in the user's terms. */
        private String reason;
    }
}
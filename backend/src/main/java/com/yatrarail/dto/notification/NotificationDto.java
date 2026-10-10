package com.yatrarail.dto.notification;

import com.yatrarail.entity.Notification;
import com.yatrarail.entity.NotificationType;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Optional;

@Data
@Builder
public class NotificationDto {
    private Long id;
    private String type;

    /** Human name for {@link #type}, or the raw value for a type this build does not know. */
    private String typeLabel;

    /** The preference group this type belongs to, e.g. "Waitlist updates". */
    private String category;

    private String title;
    private String message;
    private Boolean isRead;
    private LocalDateTime createdAt;

    /**
     * True for a system-wide announcement (a row with no single recipient).
     * <p>
     * The client needs this because an announcement's read state lives in a per-user receipt rather
     * than on the row, so "read" for a broadcast is a statement about the reader, not about the
     * notice. Anything that wants to group or label the feed can use it; nothing security-relevant
     * does.
     */
    private Boolean announcement;

    public static NotificationDto from(Notification notification) {
        return from(notification, false, Boolean.TRUE.equals(notification.getIsRead()));
    }

    /**
     * @param announcement whether this row is a broadcast
     * @param read         the caller's read state, which for a broadcast comes from their receipt
     *                     rather than from the shared row
     */
    public static NotificationDto from(Notification notification, boolean announcement, boolean read) {
        Optional<NotificationType> type = NotificationType.fromWire(notification.getType());
        return NotificationDto.builder()
                .id(notification.getId())
                .type(notification.getType())
                // An unknown legacy type still has to render. Losing a notification because this
                // build has not heard of its label is a worse failure than showing the raw string.
                .typeLabel(type.map(NotificationType::getLabel).orElse(notification.getType()))
                .category(type.map(NotificationType::getCategoryLabel).orElse("Other"))
                .title(notification.getTitle())
                .message(notification.getMessage())
                .isRead(read)
                .createdAt(notification.getCreatedAt())
                .announcement(announcement)
                .build();
    }
}
package com.yatrarail.controller;

import com.yatrarail.dto.common.ApiResponse;
import com.yatrarail.dto.notification.NotificationDto;
import com.yatrarail.dto.notification.NotificationPreferenceDto;
import com.yatrarail.dto.notification.UpdateNotificationPreferenceRequest;
import com.yatrarail.security.UserPrincipal;
import com.yatrarail.service.NotificationPreferenceService;
import com.yatrarail.service.NotificationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;
    private final NotificationPreferenceService preferenceService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<NotificationDto>>> getAll(@AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(notificationService.getForUser(principal.getId())));
    }

    /**
     * Personal unread notifications plus announcements this user has not acknowledged. Announcements
     * were invisible to this count before Phase 9, so a newly published notice produced no badge.
     */
    @GetMapping("/unread-count")
    public ResponseEntity<ApiResponse<Map<String, Long>>> unreadCount(@AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(Map.of("count", notificationService.unreadCount(principal.getId()))));
    }

    /**
     * The caller's own settings. Reading them never writes: a user who has never changed anything is
     * served the defaults and no row is created.
     */
    @GetMapping("/preferences")
    public ResponseEntity<ApiResponse<NotificationPreferenceDto>> preferences(
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(preferenceService.get(principal.getId())));
    }

    @PutMapping("/preferences")
    public ResponseEntity<ApiResponse<NotificationPreferenceDto>> updatePreferences(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody UpdateNotificationPreferenceRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Notification preferences saved",
                preferenceService.update(principal.getId(), request)));
    }

    /**
     * Acknowledges one notification. Safe to call twice: an announcement records one receipt however
     * many times it is opened.
     */
    @PatchMapping("/{id}/read")
    public ResponseEntity<ApiResponse<NotificationDto>> markRead(@AuthenticationPrincipal UserPrincipal principal,
                                                                 @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(notificationService.markAsRead(id, principal.getId())));
    }

    @PatchMapping("/read-all")
    public ResponseEntity<ApiResponse<Object>> markAllRead(@AuthenticationPrincipal UserPrincipal principal) {
        notificationService.markAllAsRead(principal.getId());
        return ResponseEntity.ok(ApiResponse.ok("All notifications marked as read", null));
    }
}
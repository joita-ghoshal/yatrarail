package com.yatrarail.controller;

import com.yatrarail.dto.auth.ConvertTemporaryAccountRequest;
import com.yatrarail.dto.auth.CreateTemporaryAccountRequest;
import com.yatrarail.dto.auth.TemporarySessionDto;
import com.yatrarail.dto.auth.TemporarySessionResponse;
import com.yatrarail.dto.common.ApiResponse;
import com.yatrarail.exception.RateLimitExceededException;
import com.yatrarail.security.GuestSessionRateLimiter;
import com.yatrarail.security.UserPrincipal;
import com.yatrarail.service.TemporaryAccountService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Guest one-time session endpoints.
 * <p>
 * Only opening a session is public; introspecting, ending and converting all require a live
 * temporary token, and {@code TemporaryAccountService} re-checks that the caller really is a guest
 * session. A permanent account calling these gets a 403 rather than someone else's session data.
 */
@RestController
@RequestMapping("/api/auth/temporary")
@RequiredArgsConstructor
public class TemporaryAccountController {

    private final TemporaryAccountService temporaryAccountService;
    private final GuestSessionRateLimiter rateLimiter;

    @PostMapping
    public ResponseEntity<ApiResponse<TemporarySessionResponse>> create(
            @Valid @RequestBody CreateTemporaryAccountRequest request,
            HttpServletRequest httpRequest) {
        String ip = clientIp(httpRequest);
        if (rateLimiter.isBlocked(ip)) {
            throw new RateLimitExceededException("Too many guest sessions from this device. Please try again later.");
        }
        rateLimiter.record(ip);
        return ResponseEntity.ok(
                ApiResponse.ok("Guest session started", temporaryAccountService.create(request)));
    }

    @GetMapping("/session")
    public ResponseEntity<ApiResponse<TemporarySessionDto>> session(@AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(temporaryAccountService.introspect(principal.getId())));
    }

    @PostMapping("/session/end")
    public ResponseEntity<ApiResponse<Object>> endSession(@AuthenticationPrincipal UserPrincipal principal) {
        temporaryAccountService.endSession(principal.getId());
        return ResponseEntity.ok(ApiResponse.ok("Guest session ended", null));
    }

    @PostMapping("/convert")
    public ResponseEntity<ApiResponse<TemporarySessionDto>> convert(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody ConvertTemporaryAccountRequest request) {
        return ResponseEntity.ok(
                ApiResponse.ok("Your guest session is now a permanent account",
                        temporaryAccountService.convert(principal.getId(), request)));
    }

    private String clientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        String remote = request.getRemoteAddr();
        return remote == null ? "unknown" : remote;
    }
}

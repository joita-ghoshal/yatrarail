package com.yatrarail.controller;

import com.yatrarail.dto.auth.*;
import com.yatrarail.dto.common.ApiResponse;
import com.yatrarail.exception.RateLimitExceededException;
import com.yatrarail.security.LoginAttemptService;
import com.yatrarail.security.UserPrincipal;
import com.yatrarail.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final LoginAttemptService loginAttemptService;

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<AuthResponse>> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Account created successfully", authService.register(request)));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthResponse>> login(@Valid @RequestBody LoginRequest request,
                                                           HttpServletRequest httpRequest) {
        String ip = clientIp(httpRequest);
        if (loginAttemptService.isBlocked(ip, request.getEmail())) {
            throw new RateLimitExceededException("Too many failed login attempts. Please try again later.");
        }
        try {
            AuthResponse response = authService.login(request);
            loginAttemptService.reset(ip, request.getEmail());
            return ResponseEntity.ok(ApiResponse.ok("Login successful", response));
        } catch (AuthenticationException ex) {
            loginAttemptService.recordFailure(ip, request.getEmail());
            throw ex;
        }
    }

    @PostMapping("/change-password")
    public ResponseEntity<ApiResponse<Object>> changePassword(@AuthenticationPrincipal UserPrincipal principal,
                                                              @Valid @RequestBody ChangePasswordRequest request) {
        authService.changePassword(principal.getId(), request);
        return ResponseEntity.ok(ApiResponse.ok("Password updated successfully", null));
    }

    @PutMapping("/profile")
    public ResponseEntity<ApiResponse<UserProfileDto>> updateProfile(@AuthenticationPrincipal UserPrincipal principal,
                                                                     @Valid @RequestBody UpdateProfileRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Profile updated", authService.updateProfile(principal.getId(), request)));
    }

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<UserProfileDto>> me(@AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(authService.getProfile(principal.getId())));
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
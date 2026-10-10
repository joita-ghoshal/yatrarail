package com.yatrarail.dto.auth;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Everything a guest client needs to run a session without guessing: who they are, that the
 * account is temporary, and exactly when the session dies. {@code expiresInSeconds} is supplied so
 * the UI never has to parse timestamps to render a countdown.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TemporarySessionDto {

    private Long userId;
    private String fullName;
    private String email;
    private String role;
    private String accountType;

    /** ACTIVE while the session may be used; otherwise EXPIRED or REVOKED. */
    private String sessionStatus;

    private java.time.LocalDateTime expiresAt;
    private long expiresInSeconds;
}

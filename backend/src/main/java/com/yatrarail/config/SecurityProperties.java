package com.yatrarail.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * Central, validated security configuration bound from `yatrarail.security.*` properties.
 * Binding validation is enforced at startup so a misconfigured environment fails fast
 * instead of booting with insecure defaults.
 */
@Validated
@ConfigurationProperties(prefix = "yatrarail.security")
@Data
public class SecurityProperties {

    @Valid
    private Jwt jwt = new Jwt();

    @Valid
    private Cors cors = new Cors();

    @Valid
    private Login login = new Login();

    @Data
    public static class Jwt {

        /** HMAC signing secret. Must be at least 32 characters. No default is provided; a profile/env must supply it. */
        @NotBlank(message = "yatrarail.security.jwt.secret must be configured")
        @Size(min = 32, message = "yatrarail.security.jwt.secret must be at least 32 characters")
        private String secret;

        @Min(value = 10000, message = "JWT expiration must be at least 10 seconds")
        private long expirationMs = 86400000;
    }

    @Data
    public static class Cors {

        /** Allowed frontend origins, comma separated. A literal "*" disables credentials/cookies. */
        @NotEmpty(message = "yatrarail.security.cors.allowed-origins must be configured")
        private List<String> allowedOrigins = List.of("http://localhost:5173", "http://localhost:3000");
    }

    @Data
    public static class Login {

        /** Max failed login attempts per IP+email within the window before a 429 is returned. */
        @Min(value = 1)
        private int maxAttempts = 10;

        /** Minutes over which failed attempts are counted. */
        @Min(value = 1)
        private long windowMinutes = 15;
    }
}
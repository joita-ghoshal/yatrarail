package com.yatrarail.security;

import com.yatrarail.config.SecurityProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

@Service
@RequiredArgsConstructor
public class JwtService {

    public static final String ISSUER = "yatrarail";
    public static final String AUDIENCE = "yatrarail-web";
    public static final String CLAIM_ROLE = "role";
    public static final String CLAIM_UID = "uid";
    public static final String CLAIM_NAME = "name";
    public static final String CLAIM_ACCOUNT_TYPE = "accountType";
    public static final String CLAIM_SESSION_ID = "sid";

    private final SecurityProperties securityProperties;

    private SecretKey key() {
        return Keys.hmacShaKeyFor(securityProperties.getJwt().getSecret().getBytes(StandardCharsets.UTF_8));
    }

    public String generateToken(UserPrincipal principal) {
        Map<String, Object> claims = new HashMap<>();
        claims.put(CLAIM_ROLE, principal.getRole());
        claims.put(CLAIM_UID, principal.getId());
        claims.put(CLAIM_NAME, principal.getFullName());
        claims.put(CLAIM_ACCOUNT_TYPE, principal.getAccountType());
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .issuer(ISSUER)
                .audience().add(AUDIENCE).and()
                .claims(claims)
                .subject(principal.getUsername())
                .issuedAt(new Date(now))
                .expiration(new Date(now + securityProperties.getJwt().getExpirationMs()))
                .signWith(key())
                .compact();
    }

    /** Generates a token with a custom lifetime (e.g. shorter-lived temporary-account tokens). */
    public String generateToken(UserPrincipal principal, long expirationMs) {
        return buildToken(principal, expirationMs, false);
    }

    /**
     * Issues the guest-session token. Its lifetime is hard-clamped to the account's own deadline, so
     * even a misconfigured or over-long TTL can never produce a token that outlives the session.
     * A {@code jti} is included so an issued session can be correlated in logs.
     */
    public String generateTemporaryToken(UserPrincipal principal, LocalDateTime sessionDeadline) {
        if (sessionDeadline == null) {
            throw new IllegalArgumentException("A temporary session must have a deadline");
        }
        long remainingMs = Duration.between(LocalDateTime.now(), sessionDeadline).toMillis();
        if (remainingMs <= 0) {
            throw new IllegalArgumentException("The temporary session has already ended");
        }
        long lifetime = Math.min(remainingMs, securityProperties.getJwt().getExpirationMs());
        return buildToken(principal, lifetime, true);
    }

    private String buildToken(UserPrincipal principal, long lifetimeMs, boolean includeJti) {
        Map<String, Object> claims = new HashMap<>();
        claims.put(CLAIM_ROLE, principal.getRole());
        claims.put(CLAIM_UID, principal.getId());
        claims.put(CLAIM_NAME, principal.getFullName());
        claims.put(CLAIM_ACCOUNT_TYPE, principal.getAccountType());
        if (includeJti) {
            claims.put(CLAIM_SESSION_ID, UUID.randomUUID().toString());
        }
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .issuer(ISSUER)
                .audience().add(AUDIENCE).and()
                .claims(claims)
                .subject(principal.getUsername())
                .issuedAt(new Date(now))
                .expiration(new Date(now + lifetimeMs))
                .signWith(key())
                .compact();
    }

    /**
     * Parses the token, requiring the correct issuer and audience before any claim is trusted.
     * Throws on expired/signature/manipulated tokens; callers handle it.
     */
    public Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(key())
                .requireIssuer(ISSUER)
                .requireAudience(AUDIENCE)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public String extractEmail(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    public <T> T extractClaim(String token, Function<Claims, T> resolver) {
        return resolver.apply(parseClaims(token));
    }

    public boolean isTokenValid(String token, UserDetails userDetails) {
        try {
            Claims claims = parseClaims(token);
            final String email = claims.getSubject();
            return email != null
                    && email.equals(userDetails.getUsername())
                    && claims.getExpiration().after(new Date());
        } catch (Exception e) {
            return false;
        }
    }
}
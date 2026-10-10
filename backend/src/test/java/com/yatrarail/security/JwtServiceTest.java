package com.yatrarail.security;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {

    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        com.yatrarail.config.SecurityProperties props = new com.yatrarail.config.SecurityProperties();
        props.getJwt().setSecret("unit-test-jwt-secret-that-is-long-enough-32-chars");
        props.getJwt().setExpirationMs(60000);
        jwtService = new JwtService(props);
    }

    private UserPrincipal principal() {
        com.yatrarail.entity.User user = com.yatrarail.entity.User.builder()
                .id(7L)
                .fullName("Test User")
                .email("test@example.com")
                .password("irrelevant-encoded-hash")
                .role(com.yatrarail.entity.RoleName.PASSENGER)
                .active(true)
                .build();
        return new UserPrincipal(user);
    }

    @Test
    void generateAndParseTokenRoundTripsClaims() {
        String token = jwtService.generateToken(principal());

        Claims claims = jwtService.parseClaims(token);

        assertThat(claims.getSubject()).isEqualTo("test@example.com");
        assertThat(claims.getIssuer()).isEqualTo(JwtService.ISSUER);
        assertThat(claims.getAudience()).contains(JwtService.AUDIENCE);
        assertThat(claims.get(JwtService.CLAIM_ROLE, String.class)).isEqualTo("PASSENGER");
        assertThat(claims.get(JwtService.CLAIM_UID, Integer.class)).isEqualTo(7);
        assertThat(claims.get(JwtService.CLAIM_NAME, String.class)).isEqualTo("Test User");
        assertThat(claims.get(JwtService.CLAIM_ACCOUNT_TYPE, String.class)).isNotNull();
    }
}
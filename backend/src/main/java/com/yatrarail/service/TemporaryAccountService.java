package com.yatrarail.service;

import com.yatrarail.config.SecurityProperties;
import com.yatrarail.config.TemporarySessionProperties;
import com.yatrarail.dto.auth.ConvertTemporaryAccountRequest;
import com.yatrarail.dto.auth.CreateTemporaryAccountRequest;
import com.yatrarail.dto.auth.TemporarySessionDto;
import com.yatrarail.dto.auth.TemporarySessionResponse;
import com.yatrarail.entity.RoleName;
import com.yatrarail.entity.User;
import com.yatrarail.exception.BadRequestException;
import com.yatrarail.exception.ConflictException;
import com.yatrarail.exception.ForbiddenException;
import com.yatrarail.exception.ResourceNotFoundException;
import com.yatrarail.repository.UserRepository;
import com.yatrarail.security.JwtService;
import com.yatrarail.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;

/**
 * Temporary one-time accounts for guests who want to book without registering.
 *
 * <p>Design decisions worth knowing:
 * <ul>
 *   <li>A guest is a real {@link User} row with role {@link RoleName#TEMP_PASSENGER}, so bookings,
 *       payments and notifications keep a single ownership path and need no polymorphic keys.</li>
 *   <li>It holds <em>no usable password</em>: a random secret is stored, never returned, and
 *       {@code /auth/login} refuses temporary accounts outright, so the value can never be guessed
 *       or leaked into a working credential.</li>
 *   <li>Identity is synthetic ({@code temp-<uuid>@guest.yatrarail.invalid}) so a guest session
 *       never squats a real address. The {@code .invalid} TLD is reserved by RFC 2606 and rejected
 *       by registration.</li>
 *   <li>The deadline lives in the database, not only in the JWT, so revocation and expiry take
 *       effect on the very next request instead of whenever the token happens to lapse.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TemporaryAccountService {

    /** RFC 2606 reserved TLD: can never be a real mailbox, so it cannot collide with a member. */
    public static final String RESERVED_EMAIL_DOMAIN = "guest.yatrarail.invalid";

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_EXPIRED = "EXPIRED";
    public static final String STATUS_REVOKED = "REVOKED";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final TemporarySessionProperties properties;
    private final SecurityProperties securityProperties;

    @Transactional
    public TemporarySessionResponse create(CreateTemporaryAccountRequest request) {
        String name = request.getFullName() == null ? null : request.getFullName().trim();
        if (name == null || name.isEmpty()) {
            throw new BadRequestException("Please tell us the name for this booking");
        }
        String phone = request.getPhone() == null || request.getPhone().isBlank()
                ? null : request.getPhone().trim();

        LocalDateTime expiresAt = LocalDateTime.now()
                .plusMinutes(Math.min(properties.getTtlMinutes(), properties.getMaxTtlMinutes()));

        User guest = User.builder()
                .fullName(name)
                .email(syntheticEmail())
                .phone(phone)
                // Unusable credential: high entropy, never disclosed, and login refuses the account.
                .password(passwordEncoder.encode(UUID.randomUUID().toString() + UUID.randomUUID()))
                .role(RoleName.TEMP_PASSENGER)
                .temporaryAccount(true)
                .active(true)
                .tempExpiresAt(expiresAt)
                .build();

        try {
            guest = userRepository.save(guest);
        } catch (DataIntegrityViolationException ex) {
            // Synthetic addresses collide only in the astronomically unlikely case of a UUID clash.
            throw new ConflictException("Could not start a guest session. Please try again.");
        }

        log.info("Temporary session opened for userId={} expiresAt={}", guest.getId(), expiresAt);
        return TemporarySessionResponse.builder()
                .token(jwtService.generateTemporaryToken(new UserPrincipal(guest), expiresAt))
                .session(toSessionDto(guest))
                .build();
    }

    @Transactional(readOnly = true)
    public TemporarySessionDto introspect(Long userId) {
        return toSessionDto(requireTemporary(userId));
    }

    /**
     * Ends a guest session immediately. The row is kept (bookings reference it) but the session is
     * marked revoked and the account deactivated, so any token still in the wild stops working on
     * the next request rather than at its natural expiry.
     */
    @Transactional
    public void endSession(Long userId) {
        User guest = requireTemporary(userId);
        if (guest.getTempRevokedAt() == null) {
            guest.setTempRevokedAt(LocalDateTime.now());
            guest.setActive(false);
            userRepository.save(guest);
            log.info("Temporary session ended for userId={}", userId);
        }
    }

    /**
     * Promotes the live guest session to a permanent passenger account, in place. The same row and
     * the same id are kept, so every booking made as a guest stays attached to the member's
     * history with no re-linking.
     */
    @Transactional
    public TemporarySessionDto convert(Long userId, ConvertTemporaryAccountRequest request) {
        User guest = requireTemporary(userId);
        assertSessionLive(guest, "This temporary session can no longer be converted");

        String email = request.getEmail().trim().toLowerCase(Locale.ROOT);
        userRepository.findByEmail(email).ifPresent(existing -> {
            if (!existing.getId().equals(userId)) {
                throw new ConflictException("An account with this email already exists");
            }
        });

        String fullName = request.getFullName() == null || request.getFullName().isBlank()
                ? guest.getFullName() : request.getFullName().trim();
        String phone = request.getPhone() == null || request.getPhone().isBlank()
                ? guest.getPhone() : request.getPhone().trim();

        guest.setFullName(fullName);
        guest.setEmail(email);
        guest.setPhone(phone);
        guest.setPassword(passwordEncoder.encode(request.getPassword()));
        guest.setRole(RoleName.PASSENGER);
        guest.setTemporaryAccount(false);
        guest.setTempExpiresAt(null);
        guest.setTempRevokedAt(null);

        User saved;
        try {
            saved = userRepository.save(guest);
        } catch (DataIntegrityViolationException ex) {
            // Another registration claimed the address between the check and the write.
            throw new ConflictException("An account with this email already exists");
        }
        log.info("Temporary session userId={} converted to a permanent passenger account", userId);

        // Returned as a STANDARD session view so the client can render a normal member header.
        return TemporarySessionDto.builder()
                .userId(saved.getId())
                .fullName(saved.getFullName())
                .email(saved.getEmail())
                .role(saved.getRole().name())
                .accountType(UserPrincipal.ACCOUNT_STANDARD)
                .sessionStatus(STATUS_ACTIVE)
                .expiresAt(null)
                .expiresInSeconds(0)
                .build();
    }

    /**
     * Housekeeping: deactivate anything past its deadline (so a stale token is refused even if the
     * request filter were somehow bypassed) and hard-delete expired, booking-less guests once they
     * are past the retention window. Accounts that own bookings are never deleted.
     */
    @Transactional
    public int expireStaleSessions() {
        LocalDateTime now = LocalDateTime.now();
        var expired = userRepository.findByTemporaryAccountTrueAndTempExpiresAtBeforeOrderByTempExpiresAtAsc(now);
        int revoked = 0;
        for (User guest : expired) {
            if (guest.getTempRevokedAt() == null) {
                guest.setTempRevokedAt(now);
                revoked++;
            }
            if (Boolean.TRUE.equals(guest.getActive())) {
                guest.setActive(false);
            }
        }
        if (!expired.isEmpty()) {
            userRepository.saveAll(expired);
        }

        int purged = 0;
        if (properties.getRetentionDays() > 0) {
            var purgeable = userRepository.findPurgeableTemporaryAccounts(
                    now.minusDays(properties.getRetentionDays()));
            if (!purgeable.isEmpty()) {
                userRepository.deleteAll(purgeable);
                purged = purgeable.size();
            }
        }
        if (revoked > 0 || purged > 0) {
            log.info("Temporary session cleanup: {} session(s) expired, {} purged", revoked, purged);
        }
        return revoked + purged;
    }

    private User requireTemporary(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        if (!Boolean.TRUE.equals(user.getTemporaryAccount())) {
            throw new ForbiddenException("This endpoint is only available to temporary guest sessions");
        }
        return user;
    }

    private void assertSessionLive(User user, String message) {
        UserPrincipal principal = new UserPrincipal(user);
        if (!principal.isSessionLive()) {
            throw new ForbiddenException(message);
        }
    }

    private TemporarySessionDto toSessionDto(User guest) {
        boolean revoked = guest.getTempRevokedAt() != null;
        LocalDateTime sessionDeadline = guest.getTempExpiresAt();
        // The credential can never outlive the global JWT lifetime, so the *effective* deadline is
        // the earlier of the two. Reporting the raw deadline would let the client run a countdown
        // past the moment its token actually stops working.
        LocalDateTime tokenDeadline = LocalDateTime.now()
                .plusNanos(Duration.ofMillis(securityProperties.getJwt().getExpirationMs()).toNanos());
        LocalDateTime effectiveDeadline = sessionDeadline != null && sessionDeadline.isBefore(tokenDeadline)
                ? sessionDeadline : tokenDeadline;

        boolean expired = !LocalDateTime.now().isBefore(effectiveDeadline);
        String status = revoked ? STATUS_REVOKED : (expired ? STATUS_EXPIRED : STATUS_ACTIVE);
        long secondsLeft = expired
                ? 0
                : Math.max(0, Duration.between(LocalDateTime.now(), effectiveDeadline).getSeconds());

        return TemporarySessionDto.builder()
                .userId(guest.getId())
                .fullName(guest.getFullName())
                .email(guest.getEmail())
                .role(guest.getRole().name())
                .accountType(UserPrincipal.ACCOUNT_TEMPORARY)
                .sessionStatus(status)
                .expiresAt(effectiveDeadline)
                .expiresInSeconds(secondsLeft)
                .build();
    }

    private String syntheticEmail() {
        return "temp-" + UUID.randomUUID() + "@" + RESERVED_EMAIL_DOMAIN;
    }
}

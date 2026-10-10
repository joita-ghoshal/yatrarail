package com.yatrarail.service;

import com.yatrarail.config.SecurityProperties;
import com.yatrarail.dto.auth.*;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final SecurityProperties securityProperties;

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String email = normalizeEmail(request.getEmail());
        assertNotReservedEmail(email);
        if (userRepository.existsByEmail(email)) {
            throw new ConflictException("An account with this email already exists");
        }
        User user = User.builder()
                .fullName(request.getFullName())
                .email(email)
                .phone(request.getPhone())
                .password(passwordEncoder.encode(request.getPassword()))
                .role(RoleName.PASSENGER)
                .gender(request.getGender())
                .age(request.getAge())
                .active(true)
                .build();
        try {
            user = userRepository.save(user);
        } catch (DataIntegrityViolationException ex) {
            // Unique email raced with a concurrent registration.
            throw new ConflictException("An account with this email already exists");
        }
        return buildAuthResponse(user);
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        String email = normalizeEmail(request.getEmail());
        User existing = userRepository.findByEmail(email).orElse(null);
        if (existing != null && Boolean.TRUE.equals(existing.getTemporaryAccount())) {
            // A guest account holds an unusable random secret. Refusing here keeps it from ever
            // becoming a working credential, whatever that secret looks like.
            throw new ForbiddenException("This is a temporary guest session and cannot be used to sign in");
        }
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(email, request.getPassword())
        );
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found"));
        if (!Boolean.TRUE.equals(user.getActive())) {
            throw new BadRequestException("This account has been deactivated. Please contact support.");
        }
        return buildAuthResponse(user);
    }

    @Transactional
    public void changePassword(Long userId, ChangePasswordRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        requirePermanentAccount(user, "A temporary guest session has no password to change");
        if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPassword())) {
            throw new BadRequestException("Current password is incorrect");
        }
        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);
    }

    @Transactional
    public UserProfileDto updateProfile(Long userId, UpdateProfileRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        // A guest has no permanent profile to maintain; the only way to gain one is to convert,
        // which goes through TemporaryAccountService and re-validates uniqueness itself.
        requirePermanentAccount(user, "A temporary guest session has no profile to update");
        if (request.getFullName() != null && !request.getFullName().isBlank()) {
            user.setFullName(request.getFullName().trim());
        }
        if (request.getPhone() != null && !request.getPhone().isBlank()) {
            user.setPhone(request.getPhone().trim());
        }
        if (request.getGender() != null && !request.getGender().isBlank()) {
            user.setGender(request.getGender().trim());
        }
        if (request.getAge() != null) {
            user.setAge(request.getAge());
        }
        user = userRepository.save(user);
        return UserProfileDto.from(user);
    }

    @Transactional(readOnly = true)
    public UserProfileDto getProfile(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        UserProfileDto dto = UserProfileDto.from(user);
        if (Boolean.TRUE.equals(user.getTemporaryAccount())) {
            // Report the deadline the token can actually reach, not the raw stored one. A client that
            // counted down to the stored deadline would keep believing it is signed in after its
            // credential had already expired.
            LocalDateTime tokenDeadline = LocalDateTime.now()
                    .plusNanos(Duration.ofMillis(securityProperties.getJwt().getExpirationMs()).toNanos());
            LocalDateTime stored = user.getTempExpiresAt();
            dto.setSessionExpiresAt(stored != null && stored.isBefore(tokenDeadline) ? stored : tokenDeadline);
        }
        return dto;
    }

    private AuthResponse buildAuthResponse(User user) {
        String token = jwtService.generateToken(new UserPrincipal(user));
        return AuthResponse.builder()
                .token(token)
                .userId(user.getId())
                .fullName(user.getFullName())
                .email(user.getEmail())
                .role(user.getRole().name())
                .build();
    }

    private String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }

    private void assertNotReservedEmail(String email) {
        if (email.endsWith("@" + TemporaryAccountService.RESERVED_EMAIL_DOMAIN)) {
            throw new BadRequestException("This email address cannot be used to register");
        }
    }

    private void requirePermanentAccount(User user, String message) {
        if (Boolean.TRUE.equals(user.getTemporaryAccount())) {
            throw new ForbiddenException(message);
        }
    }
}
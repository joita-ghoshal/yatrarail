package com.yatrarail.service;

import com.yatrarail.dto.admin.AdminUserDto;
import com.yatrarail.dto.admin.CreateUserRequest;
import com.yatrarail.dto.admin.UpdateUserRequest;
import com.yatrarail.dto.common.PageResponse;
import com.yatrarail.entity.RoleName;
import com.yatrarail.entity.User;
import com.yatrarail.exception.BadRequestException;
import com.yatrarail.exception.ConflictException;
import com.yatrarail.exception.ForbiddenException;
import com.yatrarail.exception.ResourceNotFoundException;
import com.yatrarail.repository.BookingRepository;
import com.yatrarail.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.Locale;

/**
 * Admin account management: creating staff, editing a profile, changing a role, deactivating.
 * <p>
 * Every mutation here is a privilege change, so the guards matter more than the happy path. The
 * rules, and why each exists:
 * <ul>
 *   <li><b>An admin cannot demote or deactivate themselves.</b> Otherwise the last admin can lock
 *       everyone out of the console with one click, and the only recovery is a database edit.</li>
 *   <li><b>The last active admin cannot be removed.</b> The same outcome, reached through a second
 *       admin's click.</li>
 *   <li><b>{@code TEMP_PASSENGER} cannot be assigned by hand.</b> A temporary account is not a role
 *       you grant; it is a lifecycle the server owns, with its own deadline and revocation rules.
 *       Granting it to a real person would create an account that silently stops working.</li>
 *   <li><b>A temporary account can never be promoted.</b> Its identity expires with the session.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class UserAdminService {

    /** Roles an admin may hand out. Everything else is server-managed. */
    private static final EnumSet<RoleName> ASSIGNABLE =
            EnumSet.of(RoleName.PASSENGER, RoleName.STAFF, RoleName.ADMIN);

    private final UserRepository userRepository;
    private final BookingRepository bookingRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public PageResponse<AdminUserDto> search(String search, String role, Boolean active, int page, int size) {
        Page<User> result = userRepository.search(
                (search != null && !search.isBlank()) ? "%" + search.trim().toLowerCase() + "%" : null,
                parseRole(role),
                active,
                PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), AuditService.MAX_PAGE_SIZE),
                        Sort.by(Sort.Direction.ASC, "id")));
        return PageResponse.of(result, this::toDto);
    }

    private AdminUserDto toDto(User user) {
        AdminUserDto dto = AdminUserDto.from(user);
        dto.setBookingCount(bookingRepository.countByUserId(user.getId()));
        return dto;
    }

    // ------------------------------------------------------------------
    // Writes
    // ------------------------------------------------------------------

    @Transactional
    public AdminUserDto createUser(CreateUserRequest request, Long actorId) {
        RoleName role = parseAssignableRole(request.getRole());
        String email = request.getEmail().trim().toLowerCase(Locale.ENGLISH);
        if (userRepository.existsByEmail(email)) {
            throw new ConflictException("An account with that email already exists");
        }
        User user = User.builder()
                .fullName(request.getFullName().trim())
                .email(email)
                .phone(request.getPhone() == null ? null : request.getPhone().trim())
                .password(passwordEncoder.encode(request.getPassword()))
                .role(role)
                .active(true)
                // Never temporary: this is a real, password-bearing account.
                .temporaryAccount(false)
                .gender(normaliseGender(request.getGender()))
                .age(request.getAge())
                .build();
        User saved = userRepository.save(user);

        auditService.record(AuditService.USER_CREATED, "USER", saved.getId(),
                describe(actorId) + " created the account " + saved.getFullName()
                        + " (" + saved.getEmail() + ") as " + role.name(), actorId);
        return AdminUserDto.from(saved);
    }

    @Transactional
    public AdminUserDto updateUser(Long userId, UpdateUserRequest request, Long actorId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        if (Boolean.TRUE.equals(user.getTemporaryAccount())) {
            throw new BadRequestException("A guest session cannot be edited; it expires on its own");
        }

        java.util.List<String> changes = new java.util.ArrayList<>(3);
        boolean roleChanged = false;

        if (isPresent(request.getFullName()) && !request.getFullName().trim().equals(user.getFullName())) {
            changes.add("name to \"" + request.getFullName().trim() + "\"");
            user.setFullName(request.getFullName().trim());
        }
        if (isPresent(request.getPhone()) && !request.getPhone().trim().equals(user.getPhone())) {
            changes.add("phone to " + request.getPhone().trim());
            user.setPhone(request.getPhone().trim());
        }
        if (isPresent(request.getGender()) && !normaliseGender(request.getGender()).equals(user.getGender())) {
            changes.add("gender to " + normaliseGender(request.getGender()));
            user.setGender(normaliseGender(request.getGender()));
        }
        if (request.getAge() != null && !request.getAge().equals(user.getAge())) {
            changes.add("age to " + request.getAge());
            user.setAge(request.getAge());
        }

        if (isPresent(request.getRole())) {
            RoleName target = parseAssignableRole(request.getRole());
            if (target != user.getRole()) {
                assertRoleChangeAllowed(user, target, actorId);
                changes.add("role from " + user.getRole().name() + " to " + target.name());
                user.setRole(target);
                roleChanged = true;
            }
        }

        if (changes.isEmpty()) {
            return toDto(user);
        }
        User saved = userRepository.save(user);
        // A role change is the more significant verb, so it gets its own action even when the same
        // request also fixed a phone number; the summary still lists every change.
        auditService.record(roleChanged ? AuditService.USER_ROLE_CHANGED : AuditService.USER_UPDATED,
                "USER", saved.getId(),
                describe(actorId) + " updated " + saved.getFullName() + ": " + String.join(", ", changes), actorId);
        return toDto(saved);
    }

    @Transactional
    public AdminUserDto setActive(Long userId, boolean active, Long actorId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        if (active == Boolean.TRUE.equals(user.getActive())) {
            return toDto(user);
        }
        if (!active) {
            if (userId.equals(actorId)) {
                // Self-deactivation would end the operator's own session and could leave nobody able
                // to reverse it.
                throw new ForbiddenException("You cannot deactivate your own account");
            }
            if (user.getRole() == RoleName.ADMIN) {
                long remainingAdmins = userRepository.countByRoleAndActiveTrue(RoleName.ADMIN);
                if (remainingAdmins <= 1) {
                    throw new BadRequestException("This is the last active admin account");
                }
            }
        }

        user.setActive(active);
        User saved = userRepository.save(user);
        auditService.record(active ? AuditService.USER_ACTIVATED : AuditService.USER_DEACTIVATED,
                "USER", saved.getId(),
                describe(actorId) + (active ? " re-activated " : " deactivated ")
                        + saved.getFullName() + " (" + saved.getEmail() + ")", actorId);
        return toDto(saved);
    }

    // ------------------------------------------------------------------
    // Guards
    // ------------------------------------------------------------------

    private void assertRoleChangeAllowed(User user, RoleName target, Long actorId) {
        if (user.getRole() == RoleName.ADMIN && target != RoleName.ADMIN) {
            if (user.getId().equals(actorId)) {
                throw new ForbiddenException("You cannot change your own role");
            }
            long remainingAdmins = userRepository.countByRoleAndActiveTrue(RoleName.ADMIN);
            if (remainingAdmins <= 1) {
                throw new BadRequestException("This is the last active admin account");
            }
        }
    }

    private RoleName parseAssignableRole(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BadRequestException("Role is required");
        }
        RoleName role;
        try {
            role = RoleName.valueOf(raw.trim().toUpperCase(Locale.ENGLISH));
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("Unknown role: " + raw);
        }
        if (!ASSIGNABLE.contains(role)) {
            throw new BadRequestException("That role cannot be assigned by an administrator");
        }
        return role;
    }

    private RoleName parseRole(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return RoleName.valueOf(raw.trim().toUpperCase(Locale.ENGLISH));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private String normaliseGender(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.trim().toUpperCase(Locale.ENGLISH);
    }

    private boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }

    /** "Priya Sharma (ADMIN)" — the prefix every audit summary starts with. */
    private String describe(Long actorId) {
        if (actorId == null) {
            return "System";
        }
        return userRepository.findById(actorId)
                .map(a -> a.getFullName() + " (" + a.getRole().name() + ")")
                .orElse("System");
    }
}

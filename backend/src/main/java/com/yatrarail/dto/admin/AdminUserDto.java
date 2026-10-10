package com.yatrarail.dto.admin;

import com.yatrarail.entity.User;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * An account as the admin console sees it.
 * <p>
 * The user list previously returned the {@code User} entity straight from the repository. That
 * worked, but it meant the admin console's JSON contract was the entity: adding a column to
 * {@code users} silently changed an API response, and there was nowhere to put a derived field such
 * as "owns bookings". This DTO makes the contract explicit.
 */
@Data
@Builder
public class AdminUserDto {
    private Long id;
    private String fullName;
    private String email;
    private String phone;
    private String role;
    private boolean active;
    private boolean temporaryAccount;
    private String gender;
    private Integer age;
    private Long bookingCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static AdminUserDto from(User user) {
        return AdminUserDto.builder()
                .id(user.getId())
                .fullName(user.getFullName())
                .email(user.getEmail())
                .phone(user.getPhone())
                .role(user.getRole().name())
                .active(Boolean.TRUE.equals(user.getActive()))
                .temporaryAccount(Boolean.TRUE.equals(user.getTemporaryAccount()))
                .gender(user.getGender())
                .age(user.getAge())
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .build();
    }
}

package com.yatrarail.dto.auth;

import com.yatrarail.entity.User;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class UserProfileDto {
    private Long id;
    private String fullName;
    private String email;
    private String phone;
    private String gender;
    private Integer age;
    private String role;
    private boolean active;
    private boolean temporaryAccount;
    private LocalDateTime sessionExpiresAt;
    private LocalDateTime createdAt;

    public static UserProfileDto from(User user) {
        boolean temporary = Boolean.TRUE.equals(user.getTemporaryAccount());
        return UserProfileDto.builder()
                .id(user.getId())
                .fullName(user.getFullName())
                .email(user.getEmail())
                .phone(user.getPhone())
                .gender(user.getGender())
                .age(user.getAge())
                .role(user.getRole() != null ? user.getRole().name() : null)
                .active(Boolean.TRUE.equals(user.getActive()))
                .temporaryAccount(temporary)
                .sessionExpiresAt(temporary ? user.getTempExpiresAt() : null)
                .createdAt(user.getCreatedAt())
                .build();
    }
}
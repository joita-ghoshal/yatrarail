package com.yatrarail.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "users", uniqueConstraints = {
        @UniqueConstraint(columnNames = "email")
}, indexes = {
        // Phase 10: the admin user list filters by role and active state, which is a low-cardinality
        // predicate combined with a high-cardinality table.
        @Index(name = "idx_user_role_active", columnList = "role, active"),
        // Phase 10: the temporary-account sweep and the per-request session check both look up
        // temporary accounts by their expiry. This index already exists from Phase 3 under this
        // exact name; it is restated here so a schema built by Hibernate alone matches the
        // migrated schema, and Phase 10 does not add a differently-named duplicate on the same
        // columns.
        @Index(name = "idx_users_temp_expiry", columnList = "temporary_account, temp_expires_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 120)
    private String fullName;

    @Column(nullable = false, unique = true, length = 150)
    private String email;

    /** Optional: guests booking through a temporary one-time account are not forced to share a number. */
    @Column(length = 15)
    private String phone;

    @JsonIgnore
    @Column(nullable = false)
    private String password;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RoleName role;

    @Column(nullable = false)
    @Builder.Default
    private Boolean active = true;

    /**
     * True for guest accounts created through the temporary one-time booking flow. Such an account
     * has no usable password, may only book/cancel its own tickets, and its session is bounded by
     * {@link #tempExpiresAt}. It is the only thing that makes a user row "temporary": the role alone
     * is not trusted, so the two are always written together.
     */
    @Column(name = "temporary_account", nullable = false)
    @Builder.Default
    private Boolean temporaryAccount = false;

    /** Hard deadline for a temporary session; enforced server-side on every request. */
    private LocalDateTime tempExpiresAt;

    /** Set when a guest ends the session early; revokes the token immediately. */
    private LocalDateTime tempRevokedAt;

    @Column(length = 10)
    private String gender;

    private Integer age;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}

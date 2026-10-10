package com.yatrarail.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * One recorded administrative action: who did what, to which thing, and when.
 * <p>
 * Append-only by convention and by design. There is no update or delete path in the service layer,
 * because an audit trail that can be edited is not an audit trail — the only question this table
 * answers is "what did the last person to touch this configuration actually do, and when".
 * <p>
 * Deliberately narrow: it records the <i>action</i>, the actor and a human-readable summary, not a
 * before/after diff. A diff would be more precise and would also make the table write-heavy and
 * unreadable; the summary is what an operator reads at 2am, and the entity's own {@code updatedAt}
 * plus the per-entity history covers the rest.
 */
@Entity
@Table(name = "audit_logs", indexes = {
        @Index(name = "idx_audit_created", columnList = "created_at"),
        @Index(name = "idx_audit_action", columnList = "action"),
        @Index(name = "idx_audit_entity", columnList = "entity_type, entity_id"),
        // Phase 10: the audit console filters by actor ("what did this admin do?") and pages by
        // creation time, which the single-column created_at index cannot serve together.
        @Index(name = "idx_audit_actor", columnList = "actor_id, created_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Stable machine-readable verb, e.g. {@code USER_ROLE_CHANGED}. Never free text. */
    @Column(nullable = false, length = 60)
    private String action;

    /** What kind of thing was touched, e.g. {@code USER}, {@code TRAIN}, {@code FARE_RULE}. */
    @Column(name = "entity_type", nullable = false, length = 40)
    private String entityType;

    /** Id of the affected row, when it has one. Nullable for actions with no single subject. */
    @Column(name = "entity_id")
    private Long entityId;

    /**
     * One line an operator can read without a second lookup, e.g.
     * {@code "Priya Sharma (ADMIN) set the role of Arjun Mehta to STAFF"}.
     * <p>
     * Denormalised on purpose: the actor's name is captured at the moment of the action, so renaming
     * an account later cannot rewrite history.
     */
    @Column(nullable = false, length = 500)
    private String summary;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_id")
    private User actor;

    /** The actor's role at the time, for the same reason the name is denormalised. */
    @Column(name = "actor_role", length = 20)
    private String actorRole;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void stampCreatedAt() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}

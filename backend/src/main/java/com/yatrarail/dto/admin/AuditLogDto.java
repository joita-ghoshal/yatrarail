package com.yatrarail.dto.admin;

import com.yatrarail.entity.AuditLog;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * One row of the admin audit trail. The actor's name and role are resolved here rather than shipped
 * as ids, because the question an auditor asks is "which person did this", not "which user id".
 */
@Data
@Builder
public class AuditLogDto {
    private Long id;
    private String action;
    private String entityType;
    private Long entityId;
    private String summary;
    private Long actorId;
    private String actorName;
    private String actorEmail;
    private String actorRole;
    private LocalDateTime createdAt;

    public static AuditLogDto from(AuditLog log) {
        var actor = log.getActor();
        return AuditLogDto.builder()
                .id(log.getId())
                .action(log.getAction())
                .entityType(log.getEntityType())
                .entityId(log.getEntityId())
                .summary(log.getSummary())
                .actorId(actor != null ? actor.getId() : null)
                .actorName(actor != null ? actor.getFullName() : "System")
                .actorEmail(actor != null ? actor.getEmail() : null)
                .actorRole(log.getActorRole() != null ? log.getActorRole()
                        : (actor != null ? actor.getRole().name() : "SYSTEM"))
                .createdAt(log.getCreatedAt())
                .build();
    }
}

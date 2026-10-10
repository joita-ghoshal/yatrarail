package com.yatrarail.service;

import com.yatrarail.dto.admin.AuditLogDto;
import com.yatrarail.dto.common.PageResponse;
import com.yatrarail.entity.AuditLog;
import com.yatrarail.entity.User;
import com.yatrarail.repository.AuditLogRepository;
import com.yatrarail.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Writes and reads the administrative audit trail.
 * <p>
 * Two design decisions worth stating:
 * <ul>
 *   <li><b>Recording never fails the operation it describes.</b> {@link #record} runs in its own
 *       transaction ({@code REQUIRES_NEW}) and swallows its own errors. A full disk or a constraint
 *       surprise must not roll back a train edit that already succeeded — losing the audit row is
 *       bad, losing the edit is worse.</li>
 *   <li><b>Reads are the only other entry point.</b> There is no update or delete, so the table is
 *       append-only by construction.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class AuditService {

    /** Page size cap, so a client cannot ask for the whole table in one response. */
    public static final int MAX_PAGE_SIZE = 100;

    private final AuditLogRepository auditLogRepository;
    private final UserRepository userRepository;

    // Stable action names. Kept as constants so the filter dropdown, the tests and the log can never
    // disagree about the spelling of a verb.
    public static final String USER_CREATED = "USER_CREATED";
    public static final String USER_UPDATED = "USER_UPDATED";
    public static final String USER_ROLE_CHANGED = "USER_ROLE_CHANGED";
    public static final String USER_ACTIVATED = "USER_ACTIVATED";
    public static final String USER_DEACTIVATED = "USER_DEACTIVATED";
    public static final String USER_PASSWORD_RESET = "USER_PASSWORD_RESET";
    public static final String STATION_CREATED = "STATION_CREATED";
    public static final String STATION_UPDATED = "STATION_UPDATED";
    public static final String STATION_ACTIVATED = "STATION_ACTIVATED";
    public static final String STATION_DEACTIVATED = "STATION_DEACTIVATED";
    public static final String TRAIN_CREATED = "TRAIN_CREATED";
    public static final String TRAIN_UPDATED = "TRAIN_UPDATED";
    public static final String TRAIN_ACTIVATED = "TRAIN_ACTIVATED";
    public static final String TRAIN_DEACTIVATED = "TRAIN_DEACTIVATED";
    public static final String TRAIN_DELETED = "TRAIN_DELETED";
    public static final String TRAIN_ROUTE_UPDATED = "TRAIN_ROUTE_UPDATED";
    public static final String COACH_ADDED = "COACH_ADDED";
    public static final String COACH_UPDATED = "COACH_UPDATED";
    public static final String COACH_ACTIVATED = "COACH_ACTIVATED";
    public static final String COACH_DEACTIVATED = "COACH_DEACTIVATED";
    public static final String QUOTA_UPDATED = "QUOTA_UPDATED";
    public static final String QUOTA_FARE_UPDATED = "QUOTA_FARE_UPDATED";
    public static final String FARE_RULE_UPDATED = "FARE_RULE_UPDATED";
    public static final String ANNOUNCEMENT_SENT = "ANNOUNCEMENT_SENT";

    /**
     * Records one action. Never throws: the caller is in the middle of a successful business
     * operation and must not be derailed by the log.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String action, String entityType, Long entityId, String summary, Long actorId) {
        try {
            User actor = actorId != null ? userRepository.findById(actorId).orElse(null) : null;
            AuditLog log = AuditLog.builder()
                    .action(action)
                    .entityType(entityType)
                    .entityId(entityId)
                    .summary(summary)
                    .actor(actor)
                    .actorRole(actor != null ? actor.getRole().name() : "SYSTEM")
                    .createdAt(LocalDateTime.now())
                    .build();
            auditLogRepository.save(log);
        } catch (RuntimeException ignored) {
            // Intentionally swallowed: see the class comment. The action itself is already committed.
        }
    }

    /**
     * The audit trail, filtered and paginated.
     * <p>
     * Dates are interpreted as whole days: {@code from=2026-01-31} starts at 00:00 and {@code to}
     * ends at 23:59:59.999 the same day, because an operator filtering "31 January" means that
     * calendar day and not a 24-hour window starting at midnight.
     */
    @Transactional(readOnly = true)
    public PageResponse<AuditLogDto> search(String action, String entityType, Long entityId, Long actorId,
                                           LocalDate from, LocalDate to, String search,
                                           int page, int size) {
        Page<AuditLog> result = auditLogRepository.search(
                blankToNull(action),
                blankToNull(entityType),
                entityId,
                actorId,
                from != null ? from.atStartOfDay() : null,
                to != null ? to.atTime(23, 59, 59, 999_000_000) : null,
                search != null && !search.isBlank()
                        ? "%" + search.trim().toLowerCase() + "%" : null,
                PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), MAX_PAGE_SIZE),
                        Sort.by(Sort.Direction.DESC, "createdAt", "id")));
        return PageResponse.of(result, AuditLogDto::from);
    }

    @Transactional(readOnly = true)
    public java.util.List<String> knownActions() {
        return auditLogRepository.findDistinctActions();
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}

package com.yatrarail.repository;

import com.yatrarail.entity.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;

/**
 * Read-only access to the audit trail.
 * <p>
 * Note the deliberate absence of {@code delete} and {@code save} overrides: {@code JpaRepository}
 * technically exposes them, but nothing in the service layer calls them, and an audit table whose
 * rows can be deleted through a repository method is one refactor away from losing history.
 */
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    /**
     * The date bounds are written as {@code created_at >= coalesce(:from, created_at)} rather than the
     * usual {@code (:from is null or created_at >= :from)}.
     * <p>
     * The second form binds an untyped NULL to a parameter that appears nowhere else in the
     * statement, and PostgreSQL refuses to guess its type ("could not determine data type of
     * parameter"). H2 accepts it, so this only fails against the real database. {@code coalesce}
     * takes its type from the column, and with no bound supplied the comparison degenerates to
     * {@code created_at >= created_at}, which is true for this NOT NULL column.
     * <p>
     * The upper bound is inclusive. The service resolves {@code to} to 23:59:59.999 of that day, so
     * "31 January" means the whole calendar day and the last few nanoseconds are included rather
     * than dropped.
     */
    @Query("""
            select a from AuditLog a
            where (:action is null or a.action = :action)
              and (:entityType is null or a.entityType = :entityType)
              and (:entityId is null or a.entityId = :entityId)
              and (:actorId is null or a.actor.id = :actorId)
              and (a.createdAt >= coalesce(:from, a.createdAt))
              and (a.createdAt <= coalesce(:to, a.createdAt))
              and (:search is null or lower(a.summary) like :search)
            """)
    Page<AuditLog> search(
            @Param("action") String action,
            @Param("entityType") String entityType,
            @Param("entityId") Long entityId,
            @Param("actorId") Long actorId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("search") String search,
            Pageable pageable);

    /** Distinct action names, so the filter dropdown cannot drift from what is actually recorded. */
    @Query("select distinct a.action from AuditLog a order by a.action")
    java.util.List<String> findDistinctActions();

    long countByActionIn(Collection<String> actions);
}

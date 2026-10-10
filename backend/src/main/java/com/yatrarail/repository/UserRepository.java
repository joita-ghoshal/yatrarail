package com.yatrarail.repository;

import com.yatrarail.entity.RoleName;
import com.yatrarail.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);

    /** Every staff member, for broadcasting an operational notice to the portal. */
    List<User> findByRoleOrderByIdAsc(RoleName role);

    /** Guest sessions whose deadline has passed, oldest first. */
    List<User> findByTemporaryAccountTrueAndTempExpiresAtBeforeOrderByTempExpiresAtAsc(LocalDateTime cutoff);

    /**
     * Guest sessions that expired more than the retention window ago and own no bookings, so the
     * cleanup job can purge them without orphaning any ticket history. Retention is measured from
     * when the session <em>died</em>, not from when the account was created, so an old guest whose
     * deadline has only just passed is not swept away instantly.
     */
    @Query("""
            select u from User u
            where u.temporaryAccount = true
              and u.tempExpiresAt < :retentionCutoff
              and not exists (select b from Booking b where b.user = u)
            """)
    List<User> findPurgeableTemporaryAccounts(@Param("retentionCutoff") LocalDateTime retentionCutoff);

    // ------------------------------------------------------------------
    // Phase 7 - paginated, filterable admin user search.
    // ------------------------------------------------------------------

    /**
     * One query for the admin users table. Every filter is optional and combined with
     * {@code (:param is null or ...)}, so a single method covers "all users", "active staff" and
     * "search for arjun" without the controller assembling criteria.
     * <p>
     * The search matches name, email or phone, because an operator who has been handed a phone
     * number will type the phone number.
     */
    @Query("""
            select u from User u
            where (:search is null
                   or lower(u.fullName) like :search
                   or lower(u.email) like :search
                   or u.phone like :search)
              and (:role is null or u.role = :role)
              and (:active is null or u.active = :active)
            """)
    org.springframework.data.domain.Page<User> search(
            @Param("search") String search,
            @Param("role") RoleName role,
            @Param("active") Boolean active,
            org.springframework.data.domain.Pageable pageable);

    /**
     * Active admins only. Used by the "you cannot remove the last admin" guard, which must count
     * <em>active</em> admins: a demoted-to-inactive admin cannot sign in to undo the change.
     */
    long countByRoleAndActiveTrue(RoleName role);

    /**
     * How many real, currently usable accounts an announcement reaches. Guest sessions are excluded
     * on purpose: they are created anonymously, expire on their own and are not an audience an
     * administrator is publishing a service notice to, so counting them would overstate reach.
     */
    long countByActiveTrueAndTemporaryAccountFalse();
}

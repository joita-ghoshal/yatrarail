package com.yatrarail.repository;

import com.yatrarail.entity.Cancellation;
import com.yatrarail.entity.RefundStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * The refund ledger.
 * <p>
 * Phase 5 turned this from "one row per booking" into "one row per cancelled passenger, plus one
 * row for a whole-booking cancellation", so the lookups are now a mix of booking-wide and
 * passenger-specific.
 */
public interface CancellationRepository extends JpaRepository<Cancellation, Long> {

    /** Every cancellation row of a booking, oldest first - the order the refunds were issued in. */
    List<Cancellation> findByBookingIdOrderByCancelledAtAsc(Long bookingId);

    /** A passenger's own cancellation row, if they have been cancelled before. */
    Optional<Cancellation> findByBookingPassengerId(Long bookingPassengerId);

    /**
     * The replay guard for a partial cancellation. The database's unique constraint already prevents
     * a duplicate row, but checking first turns the race into a clean 409 for the passenger instead
     * of an integrity exception surfacing as a 500.
     */
    boolean existsByBookingPassengerId(Long bookingPassengerId);

    /** Uniqueness guard for a generated refund reference, the way the payment reference is guarded. */
    boolean existsByRefundReference(String refundReference);

    /**
     * Refunds for a set of bookings in one query, so reconciliation does not N+1.
     * <p>
     * The empty case is handled here rather than at each call site: three of the callers pass
     * "this user's bookings" or "every booking", and a brand-new installation has none of either.
     * Rendering that as an empty {@code IN ()} is at best a syntax error and at worst a
     * dialect-specific surprise, so it is short-circuited to an empty result.
     */
    default List<Cancellation> findByBookingIds(Collection<Long> bookingIds) {
        if (bookingIds == null || bookingIds.isEmpty()) {
            return List.of();
        }
        return findByBookingIdsIn(bookingIds);
    }

    @Query("select c from Cancellation c where c.booking.id in :bookingIds")
    List<Cancellation> findByBookingIdsIn(@Param("bookingIds") Collection<Long> bookingIds);

    /** The whole ledger, newest first. Backs the admin refund view. */
    List<Cancellation> findAllByOrderByCancelledAtDesc();

    /**
     * Phase 10 — the admin refund ledger, paged and searchable in the database. Matches the refund
     * reference, the booking reference, the account holder and (for a partial cancellation) the
     * passenger, which is every handle an operator is given when a refund is queried. The whole
     * booking cancellation has a null passenger, hence the guarded clause.
     */
    @Query("""
            select c from Cancellation c
            where (:search is null
                   or lower(c.refundReference) like :search
                   or lower(c.booking.bookingReference) like :search
                   or lower(c.booking.user.fullName) like :search
                   or (c.bookingPassenger is not null and lower(c.bookingPassenger.fullName) like :search))
            """)
    Page<Cancellation> search(@Param("search") String search, Pageable pageable);

    long countByRefundStatus(RefundStatus status);

    /** Total refunded for a status. Used by the reconciliation totals; the ledger never drifts. */
    @Query("select coalesce(sum(c.refundAmount), 0) from Cancellation c where c.refundStatus = :status")
    BigDecimal sumRefundAmountByStatus(@Param("status") RefundStatus status);
}

package com.yatrarail.repository;

import com.yatrarail.entity.PaymentAttempt;
import com.yatrarail.entity.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/**
 * Phase 5 — reads the append-only payment ledger. There is deliberately no update method: a new
 * attempt is a new row, and {@link PaymentRepository} remains the single settled answer.
 */
public interface PaymentAttemptRepository extends JpaRepository<PaymentAttempt, Long> {

    /** A booking's attempts in the order they were made, so a failure is visible before its retry. */
    List<PaymentAttempt> findByBookingIdOrderByCreatedAtAsc(Long bookingId);

    /** A passenger's own payment history, newest first. */
    List<PaymentAttempt> findByBookingUserIdOrderByCreatedAtDesc(Long userId);

    /** Every attempt, newest first. Read by the admin reconciliation report. */
    List<PaymentAttempt> findAllByOrderByCreatedAtDesc();

    boolean existsByBookingId(Long bookingId);

    /**
     * Checked by the reference generator alongside the settled table, so a reference quoted in a
     * support conversation always identifies exactly one row in the whole system.
     */
    boolean existsByTransactionReference(String transactionReference);

    long countByStatus(PaymentStatus status);

    /**
     * Attempt count and settled value per status, in one round trip.
     *
     * @return rows of {@code [PaymentStatus, count, totalAmount]}.
     */
    @Query("select a.status, count(a), coalesce(sum(a.amount), 0) "
         + "from PaymentAttempt a group by a.status")
    List<Object[]> summariseByStatus();

    /**
     * Phase 10 — attempt totals for a page of bookings, in one query.
     * <p>
     * The admin payment ledger shows "2 attempts, 1 failed" per row. Assembling a page of rows by
     * querying attempts once per booking would be an N+1 across the page; grouping by booking id
     * fetches the whole page's counts in a single round trip. Callers must short-circuit an empty id
     * set, since {@code in ()} is not valid SQL.
     *
     * @return rows of {@code [bookingId, attemptCount, failedCount]}.
     */
    @Query("select a.booking.id, count(a), "
         + "sum(case when a.status = com.yatrarail.entity.PaymentStatus.FAILED then 1 else 0 end) "
         + "from PaymentAttempt a where a.booking.id in :bookingIds group by a.booking.id")
    List<Object[]> summariseByBookingIds(@Param("bookingIds") Collection<Long> bookingIds);
}

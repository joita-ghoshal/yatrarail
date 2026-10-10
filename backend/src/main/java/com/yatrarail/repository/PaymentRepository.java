package com.yatrarail.repository;

import com.yatrarail.entity.Payment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {
    Optional<Payment> findByBookingId(Long bookingId);

    Optional<Payment> findByIdempotencyKey(String idempotencyKey);

    boolean existsByTransactionReference(String transactionReference);

    /**
     * Phase 10 — the admin payment ledger, paged and searchable in the database.
     * <p>
     * Before this the console loaded every settled payment into memory and filtered in the browser:
     * fine at a hundred rows, a browser-tab killer at a hundred thousand. The search matches what an
     * operator actually types — a booking reference, a passenger, a train name or number — and the
     * ordering is supplied by the caller's {@link Pageable}, so the page is chosen in SQL.
     */
    @Query("""
            select p from Payment p
            where (:search is null
                   or lower(p.booking.bookingReference) like :search
                   or lower(p.booking.user.fullName) like :search
                   or lower(p.booking.train.name) like :search
                   or lower(p.booking.train.trainNumber) like :search)
            """)
    Page<Payment> search(@Param("search") String search, Pageable pageable);
}

package com.yatrarail.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "payments", indexes = {
        // Phase 10: the admin payment ledger is paged newest-first, which is an ORDER BY created_at
        // over the whole table. booking_id is already indexed by its unique constraint.
        @Index(name = "idx_payment_created", columnList = "created_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "booking_id", nullable = false, unique = true)
    private Booking booking;

    @Column(nullable = false, length = 20)
    private String method; // UPI, CARD, NETBANKING (mock/simulated)

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    @Column(nullable = false, unique = true, length = 40)
    private String transactionReference;

    /**
     * Client-supplied idempotency key for the payment attempt. Replaying the same key returns the
     * already-processed result instead of charging twice, which is what stops an impatient
     * double-click on "Pay" from creating a duplicate transaction.
     */
    @Column(name = "idempotency_key", unique = true, length = 80)
    private String idempotencyKey;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}

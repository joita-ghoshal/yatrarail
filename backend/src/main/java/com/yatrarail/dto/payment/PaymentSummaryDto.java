package com.yatrarail.dto.payment;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Phase 5 — a passenger's payment history with the running totals that make it useful.
 * <p>
 * The totals are the point. A bare list of transactions is something the database can already do;
 * what a passenger actually wants to know is how much they have paid in total and how much of it
 * has come back, which is also what a support conversation needs to start from.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentSummaryDto {

    private List<PaymentHistoryEntryDto> attempts;

    /** Every attempt the passenger ever made, settled or not. */
    private BigDecimal totalAttempted;

    /** Only the attempts that actually took money. */
    private BigDecimal totalSettled;

    /** Money returned by cancellation refunds. */
    private BigDecimal totalRefunded;

    /** {@code totalSettled − totalRefunded}: what the passenger has actually lost. */
    private BigDecimal netPaid;

    private long attemptCount;
    private long failedAttemptCount;

    /** The last time money moved in either direction, so the history can be ordered confidently. */
    private LocalDateTime lastActivityAt;
}

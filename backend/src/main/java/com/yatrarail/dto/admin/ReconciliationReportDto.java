package com.yatrarail.dto.admin;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Phase 5 — the payment reconciliation report.
 * <p>
 * <b>What this is for.</b> Before Phase 5 the only record of a payment was a mutable current state
 * on the booking, so nothing could be checked against anything else: there was no second source to
 * reconcile <i>against</i>. With an append-only attempt ledger and per-passenger refund rows there
 * finally is one, and this report is the check.
 * <p>
 * The report is a genuine cross-check, not a restatement of the ledger: every finding compares two
 * independently-written facts (a payment row against a booking status, a refund total against a
 * settled amount) and only reports where they disagree. A clean report therefore means the two
 * agree, not that nothing was looked at.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReconciliationReportDto {

    private LocalDateTime generatedAt;

    // ---- money totals, straight from the ledger ----
    private BigDecimal totalCollected;
    private BigDecimal totalRefunded;
    private BigDecimal totalFailedAttempts;
    private BigDecimal netCollected;

    // ---- counts ----
    private long paymentAttemptCount;
    private long settledPaymentCount;
    private long failedPaymentCount;
    private long refundCount;
    private long pendingRefundCount;
    private long failedRefundCount;
    private long bookingCount;
    private long paidBookingCount;

    /**
     * True only when there are no CRITICAL and no WARNING findings. An INFO finding (such as a
     * partially-refunded booking) is a fact about the data, not a defect, so it does not make the
     * report fail.
     */
    private Boolean reconciled;

    private List<ReconciliationIssueDto> issues;
}

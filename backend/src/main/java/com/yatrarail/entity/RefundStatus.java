package com.yatrarail.entity;

/**
 * Phase 5 — the lifecycle of a refund, as a value rather than free text.
 * <p>
 * The Phase 2 {@code cancellations.refund_status} column was a {@code varchar} that only ever held
 * {@code PENDING} or {@code PROCESSED}. The refund pipeline now distinguishes three outcomes, and
 * the difference matters operationally: a {@link #FAILED} refund is money that was promised to a
 * passenger and did not arrive, so it is the one state an operator has to act on — which is exactly
 * what the admin reconciliation report counts.
 */
public enum RefundStatus {
    /** A refund has been calculated and promised, but no money has moved yet. */
    PENDING,
    /** The refund was settled successfully (this demo settles refunds immediately). */
    PROCESSED,
    /** The refund could not be settled and needs manual follow-up. */
    FAILED
}

package com.yatrarail.entity;

/**
 * Phase 5 — how much of a booking a single cancellation row covers.
 * <p>
 * A booking may be cancelled in one go or passenger by passenger, and the refund maths is not the
 * same either way: the flat platform fee is a booking-level charge, so it is returned in full only
 * when the <i>whole</i> booking goes, and never on a partial cancellation. Recording the scope on
 * the row is what lets the refund ledger say which rule was applied months later, instead of
 * leaving an operator to infer it from a total.
 */
public enum CancellationScope {
    /** Every passenger of the booking was cancelled by this action. */
    FULL,
    /** A strict subset of the booking's passengers was cancelled. */
    PARTIAL
}

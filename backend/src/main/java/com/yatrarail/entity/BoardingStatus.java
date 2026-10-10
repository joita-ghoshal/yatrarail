package com.yatrarail.entity;

/**
 * Whether a passenger actually boarded, recorded by staff at the gate.
 * <p>
 * This is deliberately <em>operational only</em>. Marking a passenger {@link #NO_SHOW} records what
 * happened on the platform; it never triggers a refund and never changes the booking's financial
 * state. Refunds stay an explicit, audited action through the cancellation flow, because a gate
 * marking a mis-scanned passenger as absent must not be able to move money.
 */
public enum BoardingStatus {
    /** On the manifest but not yet processed at the gate. */
    NOT_BOARDED,
    /** Scanned in and travelling. */
    BOARDED,
    /** Did not board. Recorded for the operational report; no money moves. */
    NO_SHOW
}
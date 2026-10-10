package com.yatrarail.entity;

/**
 * How much of the waitlist chatter a passenger wants.
 * <p>
 * Promotion is deliberately absent from this scale. {@link #PROMOTED_ONLY} and {@link #OFF} both
 * still deliver {@link NotificationType#WAITLIST_UPDATE} — being given a seat is the whole point of
 * waiting, and no preference setting is allowed to hide it. What the passenger is choosing between
 * is how much of the queue's <i>interior</i> they want to hear about.
 */
public enum WaitlistAlertMode {

    /** Every join and every position change. The default, and the only mode that is honest about a live queue. */
    ALL("Every movement"),

    /** Only position changes, not the initial "you are on the list" notice. */
    PROMOTED_ONLY("Position changes only"),

    /** No queue chatter at all. Promotion to a seat or an RAC berth is still delivered. */
    OFF("Only the outcome");

    private final String label;

    WaitlistAlertMode(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
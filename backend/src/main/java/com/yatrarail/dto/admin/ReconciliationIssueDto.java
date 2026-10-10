package com.yatrarail.dto.admin;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Phase 5 — one reconciliation finding: a place where the ledger and the bookings disagree.
 * <p>
 * A finding is only worth reporting if it names a booking and says what is wrong, so both are
 * mandatory. {@code severity} lets the admin page sort the things that need a human above the
 * informational counts.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReconciliationIssueDto {

    public static final String SEVERITY_CRITICAL = "CRITICAL";
    public static final String SEVERITY_WARNING = "WARNING";
    public static final String SEVERITY_INFO = "INFO";

    private Long bookingId;
    private String bookingReference;

    /** A stable machine-readable code, e.g. {@code REFUND_EXCEEDS_PAYMENT}. */
    private String code;

    /** The same thing in words, written for the administrator reading the list. */
    private String message;

    /** One of the {@code SEVERITY_*} constants. */
    private String severity;

    /** The money involved, where the finding is about money. Null for purely structural findings. */
    private BigDecimal amount;
}

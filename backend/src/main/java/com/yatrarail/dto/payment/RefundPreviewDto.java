package com.yatrarail.dto.payment;

import com.yatrarail.dto.ticket.RefundPolicyRowDto;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Phase 5 — what a passenger would get back if they cancelled right now.
 * <p>
 * Computed by exactly the same code that performs the cancellation, from the same configured
 * policy, so the number shown before the passenger commits is the number they receive. The preview
 * is read-only and holds no inventory.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RefundPreviewDto {

    private Long bookingId;
    private String bookingReference;

    /**
     * The tier the current date selects. The whole table is also returned in {@link #policy}, so a
     * passenger can see the band they fell into <i>and</i> what a different date would have paid.
     */
    private String tierLabel;
    private Integer refundPercent;
    private Long daysBeforeJourney;

    /**
     * FULL when the selection covers every passenger, PARTIAL otherwise. This is what decides the
     * platform fee, so it is stated explicitly rather than being something to infer from a total.
     */
    private String scope;

    private List<PassengerRefundLineDto> lines;

    /** What was charged for the selected passengers, fare + GST, before the platform fee. */
    private BigDecimal amountCharged;

    /** The tiered deduction on the selected passengers. */
    private BigDecimal cancellationCharge;

    /** The platform-fee share returned. Zero on a partial cancellation. */
    private BigDecimal platformFeeRefunded;

    /** The platform-fee share <i>not</i> returned, because the booking-level charge still stands. */
    private BigDecimal platformFeeForfeited;

    /** Fare + GST actually returned, after the tier. */
    private BigDecimal fareRefunded;
    private BigDecimal gstRefunded;

    /** The money back. Always {@code fareRefunded + gstRefunded + platformFeeRefunded}. */
    private BigDecimal totalRefund;

    /** The full policy table, so the terms are readable before the passenger commits. */
    private List<RefundPolicyRowDto> policy;

    /** What the platform fee was on this booking, whether or not it is being returned. */
    private BigDecimal platformFeeCharged;

    /** False once the journey has departed; the preview explains why rather than showing zero. */
    private Boolean cancellable;
    private String message;

    private LocalDate journeyDate;
}

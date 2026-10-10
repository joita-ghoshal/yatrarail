package com.yatrarail.dto.payment;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Phase 5 — a refund ledger row as shown to the passenger who owns it.
 * <p>
 * The reference and the timestamp are the two fields a passenger actually needs to quote in a
 * support conversation, so they are first-class rather than something to be dug out of a total.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RefundRecordDto {

    private Long id;
    private String bookingId;
    private String bookingReference;
    private String refundReference;

    /** Null when the row cancelled the whole booking rather than one passenger. */
    private Long bookingPassengerId;

    /** The cancelled passenger's name, or "All passengers" for a whole-booking refund. */
    private String passengerName;

    private String reason;
    private BigDecimal refundAmount;

    private String refundStatus;
    private String scope;
    private BigDecimal fareRefunded;
    private BigDecimal gstRefunded;
    private BigDecimal platformFeeRefunded;
    private BigDecimal cancellationCharge;
    private Integer refundPercent;
    private Long daysBeforeJourney;
    private LocalDateTime cancelledAt;
    private LocalDateTime processedAt;
}

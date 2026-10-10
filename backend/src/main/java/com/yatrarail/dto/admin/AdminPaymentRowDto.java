package com.yatrarail.dto.admin;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Phase 5 — one row of the admin payments table.
 * <p>
 * The admin view needs the settled row and the attempts behind it in one place, because the question
 * an administrator is actually asking is "did this booking's money behave?", and answering it
 * otherwise takes two screens and a mental join.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminPaymentRowDto {

    private Long paymentId;
    private Long bookingId;
    private String bookingReference;
    private String passengerName;

    private String trainName;
    private String trainNumber;
    private String sourceStationName;
    private String destinationStationName;
    private LocalDate journeyDate;

    private String bookingStatus;
    private String method;
    private BigDecimal amount;
    private String status;
    private String transactionReference;
    private LocalDateTime paidAt;

    /** How many attempts it took to get here. 1 for a clean first-time payment. */
    private Integer attemptCount;

    private Integer failedAttemptCount;

    private BigDecimal refundedAmount;
    private BigDecimal netCollected;
}

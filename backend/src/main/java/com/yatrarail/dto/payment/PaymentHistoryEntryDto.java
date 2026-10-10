package com.yatrarail.dto.payment;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Phase 5 — one row of a passenger's payment history, i.e. one attempt in the ledger.
 * <p>
 * The booking's identity is carried alongside each attempt so the history reads as a list of real
 * journeys rather than a list of opaque amounts, and so the passenger can go from "what did I pay"
 * straight to "which ticket was that" without a second lookup.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentHistoryEntryDto {

    private Long attemptId;
    private Long bookingId;
    private String bookingReference;
    private String trainName;
    private String trainNumber;
    private String sourceStationName;
    private String destinationStationName;
    private LocalDate journeyDate;

    private String method;
    private BigDecimal amount;

    /** INITIATED / SUCCESS / FAILED / PENDING. */
    private String status;

    private String transactionReference;
    private LocalDateTime attemptedAt;

    /**
     * True for the attempt that actually settled the booking, and false for a failure that was
     * later retried. Without this the history shows two attempts and no way to tell which one took
     * the money, which was exactly the gap Phase 5 was opened to close.
     */
    private Boolean settled;
}

package com.yatrarail.controller;

import com.yatrarail.dto.common.ApiResponse;
import com.yatrarail.dto.payment.PassengerRefundRequest;
import com.yatrarail.dto.payment.PaymentSummaryDto;
import com.yatrarail.dto.payment.RefundPreviewDto;
import com.yatrarail.dto.payment.RefundRecordDto;
import com.yatrarail.security.UserPrincipal;
import com.yatrarail.service.CancellationService;
import com.yatrarail.service.PaymentLedgerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Phase 5 — the passenger's own money: what they paid, and what they can get back.
 * <p>
 * Reading your own history and cancelling your own passengers are both scoped by the authenticated
 * principal, never by a body-supplied user id, so there is no path to seeing someone else's money.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentLedgerService paymentLedgerService;
    private final CancellationService cancellationService;

    /**
     * The passenger's full payment history, with running totals.
     * <p>
     * Includes failed attempts, because "it did not work the first time" is part of the record a
     * passenger may need to quote - and it is the only way to explain a booking that took two tries.
     */
    @GetMapping("/payments")
    public ResponseEntity<ApiResponse<PaymentSummaryDto>> history(
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(paymentLedgerService.getHistory(principal.getId())));
    }

    /** The passenger's refund records, newest first. */
    @GetMapping("/refunds")
    public ResponseEntity<ApiResponse<List<RefundRecordDto>>> refunds(
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(paymentLedgerService.getRefundsForUser(principal.getId())));
    }

    /**
     * What cancelling these passengers would return, right now. Read-only, so the UI can quote a
     * figure before the passenger commits to anything.
     */
    @GetMapping("/bookings/{bookingId}/refund-preview")
    public ResponseEntity<ApiResponse<RefundPreviewDto>> preview(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long bookingId,
            @RequestParam(required = false) List<Long> passengerIds) {
        return ResponseEntity.ok(ApiResponse.ok(
                cancellationService.preview(principal.getId(), bookingId, passengerIds)));
    }

    /**
     * Cancels the selected passengers and issues their refunds.
     * <p>
     * Sending every passenger id is equivalent to the whole-booking cancellation, and returns the
     * platform fee; a strict subset is a partial cancellation and does not.
     */
    @PostMapping("/bookings/{bookingId}/refunds")
    public ResponseEntity<ApiResponse<List<RefundRecordDto>>> cancelPassengers(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long bookingId,
            @Valid @RequestBody PassengerRefundRequest request) {
        List<RefundRecordDto> refunds =
                cancellationService.cancelPassengers(principal.getId(), bookingId, request);
        return ResponseEntity.ok(ApiResponse.ok(
                refunds.size() == 1 && "All passengers".equals(refunds.get(0).getPassengerName())
                        ? "Booking cancelled and refund issued"
                        : "Selected passengers cancelled and refunded",
                refunds));
    }
}

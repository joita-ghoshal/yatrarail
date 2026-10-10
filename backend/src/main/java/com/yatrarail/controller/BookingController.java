package com.yatrarail.controller;

import com.yatrarail.dto.booking.*;
import com.yatrarail.dto.common.ApiResponse;
import com.yatrarail.dto.staff.AssistanceDto;
import com.yatrarail.dto.staff.AssistanceRequestBody;
import com.yatrarail.entity.RoleName;
import com.yatrarail.security.UserPrincipal;
import com.yatrarail.service.AssistanceService;
import com.yatrarail.service.BookingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/bookings")
@RequiredArgsConstructor
public class BookingController {

    private final BookingService bookingService;
    private final AssistanceService assistanceService;

    /**
     * Creates a booking and holds seats for the payment window.
     * <p>
     * Send an {@code Idempotency-Key} header: replaying the same key returns the original booking
     * instead of creating a second one, so a double-click or a retried request cannot double-book.
     */
    @PostMapping
    public ResponseEntity<ApiResponse<BookingResponseDto>> create(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateBookingRequest request) {
        BookingResponseDto result = bookingService.createBooking(principal.getId(), request, idempotencyKey);
        return ResponseEntity.ok(ApiResponse.ok(
                "Booking created. Please complete payment within the payment window to confirm your seats.",
                result));
    }

    /**
     * Read-only fare + availability preview for the selected segment.
     * <p>
     * Phase 4: the quote now needs the caller, because eligibility for a reserved quota depends on
     * who is asking (the staff quota on their account role, the rest on the passenger details sent
     * with the request). It returns the full itemised fare breakdown and the live per-quota
     * availability, so the price and the quota shown here are exactly what the booking will apply.
     */
    @PostMapping("/quote")
    public ResponseEntity<ApiResponse<BookingQuoteDto>> quote(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody BookingQuoteRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(
                bookingService.quote(request, RoleName.valueOf(principal.getRole()))));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<BookingResponseDto>>> myBookings(
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(bookingService.getBookingsForUser(principal.getId())));
    }

    @GetMapping("/{bookingId}")
    public ResponseEntity<ApiResponse<BookingResponseDto>> details(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long bookingId) {
        return ResponseEntity.ok(ApiResponse.ok(bookingService.getBookingDetails(principal.getId(), bookingId)));
    }

    /**
     * Processes (or replays) the mock payment. An {@code Idempotency-Key} header makes the call safe
     * to retry — the same key returns the already-processed result instead of charging twice.
     */
    @PostMapping("/{bookingId}/payment")
    public ResponseEntity<ApiResponse<BookingResponseDto>> pay(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long bookingId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ProcessPaymentRequest request) {
        BookingResponseDto result = bookingService.processPayment(principal.getId(), bookingId, request, idempotencyKey);
        String message = "SUCCESS".equals(result.getPayment().getStatus())
                ? "Payment successful. Booking status: " + result.getStatus().toLowerCase() + "."
                : "Payment failed. You can retry.";
        return ResponseEntity.ok(ApiResponse.ok(message, result));
    }

    @PostMapping("/{bookingId}/cancel")
    public ResponseEntity<ApiResponse<BookingResponseDto>> cancel(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long bookingId,
            @Valid @RequestBody CancelBookingRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Booking cancelled",
                bookingService.cancelBooking(principal.getId(), bookingId, request)));
    }

    /**
     * Raises an assistance request for one passenger of a booking the caller owns.
     * <p>
     * Lives under {@code /api/bookings} rather than {@code /api/staff} because it is the passenger
     * who raises it; staff consume it from their own portal. Ownership is enforced in the service,
     * and a passenger id belonging to somebody else's booking is a **404**, not a 403, so the
     * endpoint cannot be used to confirm that another booking exists.
     */
    @PostMapping("/{bookingId}/assistance-requests")
    public ResponseEntity<ApiResponse<AssistanceDto>> raiseAssistance(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long bookingId,
            @Valid @RequestBody AssistanceRequestBody request) {
        return ResponseEntity.ok(ApiResponse.ok("Assistance request sent to railway staff",
                assistanceService.raise(bookingId, request, principal.getId())));
    }

    /** The caller's own assistance requests for a booking, newest first. */
    @GetMapping("/{bookingId}/assistance-requests")
    public ResponseEntity<ApiResponse<List<AssistanceDto>>> myAssistanceRequests(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long bookingId) {
        bookingService.assertOwnedBy(principal.getId(), bookingId);
        return ResponseEntity.ok(ApiResponse.ok(assistanceService.listForBooking(bookingId)));
    }
}

package com.yatrarail.controller;

import com.yatrarail.dto.common.ApiResponse;
import com.yatrarail.dto.ticket.TicketDto;
import com.yatrarail.dto.ticket.TicketVerificationResultDto;
import com.yatrarail.dto.ticket.TicketVerifyRequest;
import com.yatrarail.entity.RoleName;
import com.yatrarail.security.UserPrincipal;
import com.yatrarail.service.TicketService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * Phase 5 — the printable ticket and its QR code.
 * <p>
 * Two separate authorities, deliberately:
 * <ul>
 *   <li>{@code GET /tickets/bookings/{id}} is <b>owner-only</b>. A ticket is a personal document, so
 *       it is never handed to staff or admins here - a 404 is returned for someone else's booking,
 *       exactly as for one that does not exist.</li>
 *   <li>{@code POST /tickets/verify} is for whoever is checking a passenger at a gate. A passenger
 *       may only verify their own ticket; STAFF and ADMIN may verify any.</li>
 * </ul>
 * Verification returns HTTP 200 with a verdict rather than a 4xx, because a gate needs to
 * distinguish a forgery from a cancellation from an unreadable code and act differently on each.
 */
@RestController
@RequestMapping("/api/tickets")
@RequiredArgsConstructor
public class TicketController {

    private final TicketService ticketService;

    /** The printable ticket for a booking, including the signed QR payload. */
    @GetMapping("/bookings/{bookingId}")
    public ResponseEntity<ApiResponse<TicketDto>> getTicket(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long bookingId) {
        return ResponseEntity.ok(ApiResponse.ok(ticketService.getTicket(principal.getId(), bookingId)));
    }

    /**
     * Checks a scanned QR payload against the live booking.
     * <p>
     * Always HTTP 200: the {@code outcome} field is the verdict, and a scanner pointed at a
     * non-YatraRail code is an expected event rather than a client error.
     */
    @PostMapping("/verify")
    public ResponseEntity<ApiResponse<TicketVerificationResultDto>> verify(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody TicketVerifyRequest request) {
        RoleName role = RoleName.valueOf(principal.getRole());
        boolean canReadAnyBooking = role == RoleName.ADMIN || role == RoleName.STAFF;
        return ResponseEntity.ok(ApiResponse.ok(
                ticketService.verify(principal.getId(), canReadAnyBooking, request.getPayload())));
    }
}

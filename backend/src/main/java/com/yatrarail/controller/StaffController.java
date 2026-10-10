package com.yatrarail.controller;

import com.yatrarail.dto.common.ApiResponse;
import com.yatrarail.dto.staff.AssistanceDto;
import com.yatrarail.dto.staff.AssistanceUpdateRequest;
import com.yatrarail.dto.staff.BoardingRequest;
import com.yatrarail.dto.staff.IncidentDto;
import com.yatrarail.dto.staff.IncidentRequest;
import com.yatrarail.dto.staff.IncidentUpdateRequest;
import com.yatrarail.dto.staff.StaffDashboardDto;
import com.yatrarail.dto.staff.StaffPassengerDto;
import com.yatrarail.dto.staff.StaffTrainDto;
import com.yatrarail.exception.BadRequestException;
import com.yatrarail.security.UserPrincipal;
import com.yatrarail.service.AssistanceService;
import com.yatrarail.service.BoardingService;
import com.yatrarail.service.StaffDashboardService;
import com.yatrarail.service.StaffService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * Railway Staff module: the operational views and actions a conductor needs on the day.
 * <p>
 * Read-only master data (trains, stations, fares, users) stays admin-only, enforced in
 * SecurityConfig. What staff <em>can</em> write here is strictly the operational record of a
 * journey day: gate check-in, incident reports and assistance handling. None of it moves money --
 * notably, marking a passenger NO_SHOW is a platform observation, never a refund.
 */
@RestController
@RequestMapping("/api/staff")
@RequiredArgsConstructor
public class StaffController {

    private final StaffService staffService;
    private final StaffDashboardService dashboardService;
    private final BoardingService boardingService;
    private final AssistanceService assistanceService;

    // ------------------------------------------------------------------
    // Dashboard and manifest
    // ------------------------------------------------------------------

    @GetMapping("/dashboard")
    public ResponseEntity<ApiResponse<StaffDashboardDto>> dashboard(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate journeyDate) {
        return ResponseEntity.ok(ApiResponse.ok(dashboardService.getDashboard(journeyDate == null ? LocalDate.now() : journeyDate)));
    }

    @GetMapping("/trains")
    public ResponseEntity<ApiResponse<List<StaffTrainDto>>> getActiveTrains() {
        return ResponseEntity.ok(ApiResponse.ok(staffService.getActiveTrains()));
    }

    @GetMapping("/trains/{trainId}/passengers")
    public ResponseEntity<ApiResponse<List<StaffPassengerDto>>> getPassengerList(
            @PathVariable Long trainId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate journeyDate) {
        return ResponseEntity.ok(ApiResponse.ok(staffService.getPassengerManifest(trainId, journeyDate)));
    }

    // ------------------------------------------------------------------
    // Gate check-in
    // ------------------------------------------------------------------

    /**
     * Records one passenger's boarding state. Deliberately does not refund or cancel anything --
     * see {@link BoardingService} for why a mis-scan must never be able to move money.
     */
    @PatchMapping("/passengers/{passengerId}/boarding")
    public ResponseEntity<ApiResponse<Object>> markBoarding(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long passengerId,
            @Valid @RequestBody BoardingRequest request) {
        BoardingService.BoardingResult result = boardingService.markBoardingWithDetail(passengerId, request, principal.getId());
        return ResponseEntity.ok(ApiResponse.ok("Boarding status recorded", result));
    }

    // ------------------------------------------------------------------
    // Incidents
    // ------------------------------------------------------------------

    @GetMapping("/incidents")
    public ResponseEntity<ApiResponse<List<IncidentDto>>> listIncidents(
            @RequestParam(required = false) String journeyDate,
            @RequestParam(required = false) String status) {
        return ResponseEntity.ok(ApiResponse.ok(boardingService.listIncidents(parseDate(journeyDate), status)));
    }

    @PostMapping("/incidents")
    public ResponseEntity<ApiResponse<IncidentDto>> reportIncident(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody IncidentRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Incident reported", boardingService.reportIncident(request, principal.getId())));
    }

    @PatchMapping("/incidents/{incidentId}")
    public ResponseEntity<ApiResponse<IncidentDto>> updateIncident(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long incidentId,
            @Valid @RequestBody IncidentUpdateRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(boardingService.updateIncidentStatus(incidentId, request, principal.getId())));
    }

    // ------------------------------------------------------------------
    // Assistance requests
    // ------------------------------------------------------------------

    @GetMapping("/assistance")
    public ResponseEntity<ApiResponse<List<AssistanceDto>>> listAssistance(
            @RequestParam(required = false) String journeyDate,
            @RequestParam(required = false) String status) {
        return ResponseEntity.ok(ApiResponse.ok(assistanceService.list(parseDate(journeyDate), status)));
    }

    @PatchMapping("/assistance/{requestId}")
    public ResponseEntity<ApiResponse<AssistanceDto>> updateAssistance(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long requestId,
            @Valid @RequestBody AssistanceUpdateRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(assistanceService.update(requestId, request, principal.getId())));
    }

    /**
     * Parses an optional date parameter. A malformed date is a 400 rather than a 500, and a missing
     * one falls back to today so the staff console opens on the current shift without a query param.
     */
    private LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return LocalDate.now();
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw new BadRequestException("Journey date must be in yyyy-MM-dd format");
        }
    }
}
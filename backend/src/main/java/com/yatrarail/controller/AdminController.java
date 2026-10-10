package com.yatrarail.controller;

import com.yatrarail.dto.admin.*;
import com.yatrarail.dto.booking.BookingResponseDto;
import com.yatrarail.dto.common.ApiResponse;
import com.yatrarail.dto.common.PageResponse;
import com.yatrarail.dto.fare.FareBreakdownDto;
import com.yatrarail.dto.fare.FarePreviewRequest;
import com.yatrarail.dto.notification.AnnouncementSummaryDto;
import com.yatrarail.dto.notification.NotificationDto;
import com.yatrarail.dto.payment.RefundRecordDto;
import com.yatrarail.dto.quota.QuotaConfigDto;
import com.yatrarail.dto.quota.QuotaConfigRequest;
import com.yatrarail.dto.quota.QuotaFareDto;
import com.yatrarail.dto.quota.QuotaFareRequest;
import com.yatrarail.dto.train.RouteStationDto;
import com.yatrarail.entity.*;
import com.yatrarail.exception.BadRequestException;
import com.yatrarail.exception.ResourceNotFoundException;
import com.yatrarail.repository.FareRuleRepository;
import com.yatrarail.security.UserPrincipal;
import com.yatrarail.service.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService adminService;
    private final StationService stationService;
    private final TrainService trainService;
    private final BookingService bookingService;
    private final NotificationService notificationService;
    private final QuotaAdminService quotaAdminService;
    private final PaymentLedgerService paymentLedgerService;

    // Phase 7 services.
    private final UserAdminService userAdminService;
    private final AuditService auditService;
    private final ReportService reportService;

    // ---- Dashboard ----
    @GetMapping("/dashboard")
    public ResponseEntity<ApiResponse<DashboardStatsDto>> dashboard() {
        return ResponseEntity.ok(ApiResponse.ok(adminService.getDashboardStats()));
    }

    // ---- Users ----
    /**
     * Paginated and filterable from Phase 7. The unpaged {@code List<User>} this returned before
     * also leaked the whole account table to the browser; a page object fixes both the size problem
     * and the accidental contract.
     */
    @GetMapping("/users")
    public ResponseEntity<ApiResponse<PageResponse<AdminUserDto>>> getUsers(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) Boolean active,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.ok(
                userAdminService.search(search, role, active, page, size)));
    }

    /** Phase 7: creates a passenger, staff or admin account, including the first staff hire. */
    @PostMapping("/users")
    public ResponseEntity<ApiResponse<AdminUserDto>> createUser(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody CreateUserRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Account created",
                userAdminService.createUser(request, principal.getId())));
    }

    /** Phase 7: edits another account, including a role change. */
    @PutMapping("/users/{id}")
    public ResponseEntity<ApiResponse<AdminUserDto>> updateUser(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id,
            @Valid @RequestBody UpdateUserRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Account updated",
                userAdminService.updateUser(id, request, principal.getId())));
    }

    @PatchMapping("/users/{id}/active")
    public ResponseEntity<ApiResponse<AdminUserDto>> setUserActive(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id,
            @RequestParam boolean active) {
        return ResponseEntity.ok(ApiResponse.ok(
                userAdminService.setActive(id, active, principal.getId())));
    }

    // ---- Stations ----
    @GetMapping("/stations")
    public ResponseEntity<ApiResponse<List<Station>>> getStations() {
        return ResponseEntity.ok(ApiResponse.ok(stationService.getAll()));
    }

    @PostMapping("/stations")
    public ResponseEntity<ApiResponse<Station>> createStation(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody StationRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Station created",
                stationService.create(request, principal.getId())));
    }

    @PutMapping("/stations/{id}")
    public ResponseEntity<ApiResponse<Station>> updateStation(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id,
            @Valid @RequestBody StationRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Station updated",
                stationService.update(id, request, principal.getId())));
    }

    @PatchMapping("/stations/{id}/active")
    public ResponseEntity<ApiResponse<Station>> setStationActive(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id,
            @RequestParam boolean active) {
        return ResponseEntity.ok(ApiResponse.ok(
                stationService.setActive(id, active, principal.getId())));
    }

    // ---- Trains ----
    @GetMapping("/trains")
    public ResponseEntity<ApiResponse<List<TrainDto>>> getTrains() {
        return ResponseEntity.ok(ApiResponse.ok(trainService.getAllTrains()));
    }

    @PostMapping("/trains")
    public ResponseEntity<ApiResponse<TrainDto>> createTrain(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody TrainRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Train created",
                trainService.createTrain(request, principal.getId())));
    }

    @PutMapping("/trains/{id}")
    public ResponseEntity<ApiResponse<TrainDto>> updateTrain(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id,
            @Valid @RequestBody TrainRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Train updated",
                trainService.updateTrain(id, request, principal.getId())));
    }

    @PatchMapping("/trains/{id}/active")
    public ResponseEntity<ApiResponse<TrainDto>> setTrainActive(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id,
            @RequestParam boolean active) {
        return ResponseEntity.ok(ApiResponse.ok(
                trainService.setTrainActive(id, active, principal.getId())));
    }

    @DeleteMapping("/trains/{id}")
    public ResponseEntity<ApiResponse<Object>> deleteTrain(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id) {
        trainService.deleteTrain(id, principal.getId());
        return ResponseEntity.ok(ApiResponse.ok("Train deleted", null));
    }

    // ---- Route stops (Phase 7) ----
    /**
     * The stops on a train's route, in order.
     * <p>
     * This had no reader at all before Phase 7: the route was accepted on create and could not be
     * read back, edited, or even seen in the console.
     */
    @GetMapping("/trains/{id}/route")
    public ResponseEntity<ApiResponse<List<RouteStationDto>>> getRoute(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(trainService.getRoute(id)));
    }

    /** Replaces the whole stop list. See {@code TrainService.replaceRoute} for why it is a replace. */
    @PutMapping("/trains/{id}/route")
    public ResponseEntity<ApiResponse<List<RouteStationDto>>> replaceRoute(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id,
            @Valid @RequestBody List<RouteStationRequest> route) {
        return ResponseEntity.ok(ApiResponse.ok("Route saved",
                trainService.replaceRoute(id, route, principal.getId())));
    }

    // ---- Coaches ----
    @GetMapping("/trains/{trainId}/coaches")
    public ResponseEntity<ApiResponse<List<CoachDto>>> getCoaches(@PathVariable Long trainId) {
        return ResponseEntity.ok(ApiResponse.ok(trainService.getCoachesForTrain(trainId)));
    }

    @PostMapping("/trains/{trainId}/coaches")
    public ResponseEntity<ApiResponse<CoachDto>> addCoach(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long trainId,
            @Valid @RequestBody CoachRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Coach added",
                trainService.addCoach(trainId, request, principal.getId())));
    }

    /** Phase 7: edits a coach, refusing the two changes that would corrupt allocated seats. */
    @PutMapping("/coaches/{id}")
    public ResponseEntity<ApiResponse<CoachDto>> updateCoach(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id,
            @Valid @RequestBody CoachRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Coach updated",
                trainService.updateCoach(id, request, principal.getId())));
    }

    @PatchMapping("/coaches/{id}/active")
    public ResponseEntity<ApiResponse<CoachDto>> setCoachActive(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id,
            @RequestParam boolean active) {
        return ResponseEntity.ok(ApiResponse.ok(
                trainService.setCoachActive(id, active, principal.getId())));
    }

    // ---- Fare Rules ----
    /**
     * Phase 4: returns DTOs rather than entities, because the tariff now also carries the platform
     * fee and GST percentage that the booking stores as a snapshot.
     */
    @GetMapping("/fare-rules")
    public ResponseEntity<ApiResponse<List<FareRuleDto>>> getFareRules() {
        return ResponseEntity.ok(ApiResponse.ok(quotaAdminService.listFareRules()));
    }

    @PostMapping("/fare-rules")
    public ResponseEntity<ApiResponse<FareRuleDto>> upsertFareRule(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody FareRuleRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Fare rule saved",
                quotaAdminService.upsertFareRule(request, principal.getId())));
    }

    /**
     * Runs the real pricing code against a hypothetical journey so an administrator can see exactly
     * what a fare or quota change does. Read-only: it creates no booking and holds no seat, so it is
     * deliberately not audited.
     */
    @PostMapping("/fare-preview")
    public ResponseEntity<ApiResponse<FareBreakdownDto>> previewFare(
            @Valid @RequestBody FarePreviewRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(quotaAdminService.previewFare(request)));
    }

    // ---- Quota splits (how many seats a class keeps aside) ----

    @GetMapping("/quotas")
    public ResponseEntity<ApiResponse<List<QuotaConfigDto>>> getQuotas() {
        return ResponseEntity.ok(ApiResponse.ok(quotaAdminService.listConfigs()));
    }

    @GetMapping("/quotas/{trainId}/{seatClass}")
    public ResponseEntity<ApiResponse<QuotaConfigDto>> getQuota(@PathVariable Long trainId,
                                                                @PathVariable String seatClass) {
        return ResponseEntity.ok(ApiResponse.ok(quotaAdminService.getConfig(trainId, seatClass)));
    }

    /**
     * Creates or replaces the split for one (train, class).
     * <p>
     * Rejected when the reserved slices exceed the class's real capacity, or when they would be
     * smaller than the seats already sold under those quotas on some journey date.
     */
    @PostMapping("/quotas")
    public ResponseEntity<ApiResponse<QuotaConfigDto>> upsertQuota(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody QuotaConfigRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Quota split saved",
                quotaAdminService.upsertConfig(request, principal.getId())));
    }

    // ---- Quota concessions (how much off a quota earns) ----

    @GetMapping("/quota-fares")
    public ResponseEntity<ApiResponse<List<QuotaFareDto>>> getQuotaFares() {
        return ResponseEntity.ok(ApiResponse.ok(quotaAdminService.listQuotaFares()));
    }

    @PostMapping("/quota-fares")
    public ResponseEntity<ApiResponse<QuotaFareDto>> upsertQuotaFare(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody QuotaFareRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Quota concession saved",
                quotaAdminService.upsertQuotaFare(request, principal.getId())));
    }

    // ---- Bookings ----
    /**
     * Phase 7: paginated and filterable. Still returns the same row DTO as before, so the existing
     * admin table renders unchanged — only the envelope is different.
     */
    @GetMapping("/bookings")
    public ResponseEntity<ApiResponse<PageResponse<BookingResponseDto>>> getAllBookings(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long trainId,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) LocalDate journeyFrom,
            @RequestParam(required = false) LocalDate journeyTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.ok(bookingService.searchAllBookings(
                search, status, trainId, userId, journeyFrom, journeyTo, page, size)));
    }

    // ---- Payments and refunds (Phase 5) ----

    /**
     * A page of settled payments, with the attempt count and refunds that explain each row.
     * <p>
     * The attempt count is the useful column: a row reading "2 attempts, 1 failed" tells an
     * administrator that a passenger had a payment problem without having to ask them.
     * <p>
     * Phase 10: paged and searchable server-side ({@code search} matches booking reference,
     * passenger, train name or number), because the ledger grows with every booking made.
     */
    @GetMapping("/payments")
    public ResponseEntity<ApiResponse<PageResponse<AdminPaymentRowDto>>> getPayments(
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.ok(paymentLedgerService.getAdminPaymentsPage(page, size, search)));
    }

    /**
     * Cross-checks the payment ledger against the bookings and refunds.
     * <p>
     * Every finding compares two facts written by different code paths - a payment against the
     * booking status it should have produced, a refund total against the money that arrived - so a
     * finding means something. {@code reconciled} is false whenever any warning or critical issue
     * exists, and an INFO finding such as a partially-refunded booking does not make it fail.
     */
    @GetMapping("/payments/reconciliation")
    public ResponseEntity<ApiResponse<ReconciliationReportDto>> getReconciliation() {
        return ResponseEntity.ok(ApiResponse.ok(paymentLedgerService.reconcile()));
    }

    /**
     * A page of the refund ledger, newest first.
     * <p>
     * Phase 10: paged and searchable server-side ({@code search} matches refund reference, booking
     * reference, account holder or passenger).
     */
    @GetMapping("/refunds")
    public ResponseEntity<ApiResponse<PageResponse<RefundRecordDto>>> getRefunds(
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.ok(paymentLedgerService.getAllRefundsPage(page, size, search)));
    }

    // ---- Reports (Phase 7) ----

    /**
     * The operations report for a journey-date range. Both bounds are optional, so {@code ?to=today}
     * is "everything up to today" and an empty query is "everything".
     */
    @GetMapping("/reports/operations")
    public ResponseEntity<ApiResponse<OperationsReportDto>> operationsReport(
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to) {
        return ResponseEntity.ok(ApiResponse.ok(reportService.report(from, to)));
    }

    /** Refund money by state. Kept out of the report so a pending refund is never netted off. */
    @GetMapping("/reports/refunds")
    public ResponseEntity<ApiResponse<ReportService.RefundSummary>> refundReport() {
        return ResponseEntity.ok(ApiResponse.ok(reportService.refundSummary()));
    }

    // ---- Audit trail (Phase 7) ----

    /**
     * The administrative audit trail, newest first.
     * <p>
     * Read-only on purpose. There is no write, update or delete endpoint for audit rows anywhere in
     * the API, which is the only way to make "append-only" a property of the system rather than a
     * convention.
     */
    @GetMapping("/audit-logs")
    public ResponseEntity<ApiResponse<PageResponse<AuditLogDto>>> auditLogs(
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) Long entityId,
            @RequestParam(required = false) Long actorId,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return ResponseEntity.ok(ApiResponse.ok(
                auditService.search(action, entityType, entityId, actorId, from, to, search, page, size)));
    }

    /** The action names that actually exist, so the filter list cannot drift from the data. */
    @GetMapping("/audit-logs/actions")
    public ResponseEntity<ApiResponse<List<String>>> auditLogActions() {
        return ResponseEntity.ok(ApiResponse.ok(auditService.knownActions()));
    }

    // ---- Notifications / Announcements ----

    /**
     * Publishes one system-wide notice.
     * <p>
     * Returns a DTO rather than the {@code Notification} entity, which is the rule this codebase
     * settled on in Phase 1: an exposed entity means the next column added to the table silently
     * becomes part of the API.
     */
    @PostMapping("/announcements")
    public ResponseEntity<ApiResponse<NotificationDto>> announce(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody AnnouncementRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Announcement published",
                notificationService.announce(request.getTitle(), request.getMessage(), principal.getId())));
    }

    /**
     * Every announcement with how many accounts it reached and how many have read it.
     * <p>
     * This is the question an administrator actually has after pressing publish, and it was
     * unanswerable before Phase 9: the notice was one shared row with one read flag, so the first
     * reader marked it read for everyone and the platform could report neither a reader count nor
     * an accurate one.
     */
    @GetMapping("/announcements")
    public ResponseEntity<ApiResponse<List<AnnouncementSummaryDto>>> announcements() {
        return ResponseEntity.ok(ApiResponse.ok(notificationService.announcementsWithReach()));
    }
}

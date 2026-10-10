package com.yatrarail.service;

import com.yatrarail.dto.admin.AdminPaymentRowDto;
import com.yatrarail.dto.admin.ReconciliationIssueDto;
import com.yatrarail.dto.admin.ReconciliationReportDto;
import com.yatrarail.dto.common.PageResponse;
import com.yatrarail.dto.payment.PaymentHistoryEntryDto;
import com.yatrarail.dto.payment.PaymentSummaryDto;
import com.yatrarail.dto.payment.RefundRecordDto;
import com.yatrarail.entity.*;
import com.yatrarail.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Phase 5 — the payment ledger, and the reconciliation that reads it.
 * <p>
 * <b>Why the ledger.</b> {@link Payment} is one mutable row per booking: the settled answer. That is
 * the right shape for "was this paid for" and the wrong shape for an audit trail, because a failed
 * attempt followed by a successful one overwrites the first attempt's transaction reference, and
 * with it the only record that the passenger ever had a problem. {@link PaymentAttempt} records every
 * attempt in order and is never updated, so history, support and reconciliation all have something
 * to read.
 * <p>
 * <b>Why reconciliation is now possible at all.</b> It needs two independently-written facts to
 * compare. Before Phase 5 there was only one mutable state, which can agree with itself trivially.
 * Now a settled payment can be checked against the booking status it was supposed to produce, and a
 * refund total against the money that actually arrived — and those are written by different code
 * paths, so a disagreement is a real finding rather than a tautology.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentLedgerService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final BigDecimal ZERO = new BigDecimal("0.00");

    private final PaymentAttemptRepository paymentAttemptRepository;
    private final PaymentRepository paymentRepository;
    private final CancellationRepository cancellationRepository;
    private final BookingRepository bookingRepository;

    // ------------------------------------------------------------------ writing

    /**
     * Appends one payment attempt. Called from the booking service for both outcomes, so a failure is
     * recorded exactly as durably as a success.
     */
    @Transactional
    public PaymentAttempt recordAttempt(Booking booking, String method, BigDecimal amount,
                                        PaymentStatus status, String transactionReference,
                                        String idempotencyKey) {
        return paymentAttemptRepository.save(PaymentAttempt.builder()
                .booking(booking)
                .user(booking.getUser())
                .method(method)
                .amount(money(amount))
                .status(status)
                .transactionReference(transactionReference)
                .idempotencyKey(idempotencyKey)
                .clientInitiated(true)
                .build());
    }

    /**
     * A transaction reference unique across the settled table <i>and</i> the ledger.
     * <p>
     * Checking only {@code payments} would be a real bug, not a theoretical one: the two tables are
     * written in the same transaction, and the ledger is what an administrator searches when a
     * passenger quotes a reference, so a collision would make a genuine payment ambiguous.
     */
    @Transactional(readOnly = true)
    public String nextTransactionReference() {
        String reference;
        do {
            byte[] bytes = new byte[8];
            RANDOM.nextBytes(bytes);
            StringBuilder sb = new StringBuilder("TXN");
            for (byte b : bytes) {
                sb.append(String.format("%02X", b));
            }
            reference = sb.toString();
        } while (paymentRepository.existsByTransactionReference(reference)
                || paymentAttemptRepository
                        .existsByTransactionReference(reference));
        return reference;
    }

    // ------------------------------------------------------------------ passenger reads

    /** A passenger's own payment history, with the totals that make it worth reading. */
    @Transactional(readOnly = true)
    public PaymentSummaryDto getHistory(Long userId) {
        List<PaymentAttempt> attempts =
                paymentAttemptRepository.findByBookingUserIdOrderByCreatedAtDesc(userId);
        List<Booking> bookings = bookingRepository.findByUserIdOrderByCreatedAtDesc(userId);

        List<PaymentHistoryEntryDto> entries = new ArrayList<>(attempts.size());
        BigDecimal totalAttempted = ZERO;
        BigDecimal totalSettled = ZERO;
        long failed = 0;
        LocalDateTime lastActivity = null;

        for (PaymentAttempt attempt : attempts) {
            boolean settled = attempt.getStatus() == PaymentStatus.SUCCESS;
            Booking booking = attempt.getBooking();
            entries.add(PaymentHistoryEntryDto.builder()
                    .attemptId(attempt.getId())
                    .bookingId(booking.getId())
                    .bookingReference(booking.getBookingReference())
                    .trainName(booking.getTrain().getName())
                    .trainNumber(booking.getTrain().getTrainNumber())
                    .sourceStationName(booking.getSourceStation().getName())
                    .destinationStationName(booking.getDestinationStation().getName())
                    .journeyDate(booking.getJourneyDate())
                    .method(attempt.getMethod())
                    .amount(attempt.getAmount())
                    .status(attempt.getStatus().name())
                    .transactionReference(attempt.getTransactionReference())
                    .attemptedAt(attempt.getCreatedAt())
                    .settled(settled)
                    .build());
            totalAttempted = money(totalAttempted.add(attempt.getAmount()));
            if (settled) {
                totalSettled = money(totalSettled.add(attempt.getAmount()));
            }
            if (attempt.getStatus() == PaymentStatus.FAILED) {
                failed++;
            }
            if (lastActivity == null
                    || attempt.getCreatedAt().isAfter(lastActivity)) {
                lastActivity = attempt.getCreatedAt();
            }
        }

        BigDecimal refundedTotal = money(cancellationRepository
                .findByBookingIds(bookings.stream().map(Booking::getId).toList())
                .stream()
                .map(Cancellation::getRefundAmount)
                .reduce(ZERO, BigDecimal::add));

        return PaymentSummaryDto.builder()
                .attempts(entries)
                .totalAttempted(money(totalAttempted))
                .totalSettled(totalSettled)
                .totalRefunded(refundedTotal)
                .netPaid(money(totalSettled.subtract(refundedTotal)))
                .attemptCount(attempts.size())
                .failedAttemptCount(failed)
                .lastActivityAt(lastActivity)
                .build();
    }

    /** A passenger's own refund records, newest first. */
    @Transactional(readOnly = true)
    public List<RefundRecordDto> getRefundsForUser(Long userId) {
        List<Booking> bookings = bookingRepository.findByUserIdOrderByCreatedAtDesc(userId);
        if (bookings.isEmpty()) {
            return List.of();
        }
        return cancellationRepository
                .findByBookingIds(bookings.stream().map(Booking::getId).toList())
                .stream()
                .sorted(Comparator.comparing(Cancellation::getCancelledAt).reversed())
                .map(this::toRefundRecord)
                .toList();
    }

    // ------------------------------------------------------------------ admin reads

    /** The whole refund ledger, newest first. Admin-only at the controller. */
    @Transactional(readOnly = true)
    public List<RefundRecordDto> getAllRefunds() {
        return cancellationRepository.findAllByOrderByCancelledAtDesc().stream()
                .map(this::toRefundRecord)
                .toList();
    }

    /** One row per settled payment, with the attempt count and refunds that explain it. */
    @Transactional(readOnly = true)
    public List<AdminPaymentRowDto> getAdminPayments() {
        List<Booking> bookings = bookingRepository.findAllByOrderByCreatedAtDesc();
        Map<Long, Booking> byId = bookings.stream()
                .collect(Collectors.toMap(Booking::getId, Function.identity()));
        List<Payment> payments = paymentRepository.findAll();

        Map<Long, Long> attemptCounts = new HashMap<>();
        Map<Long, Long> failedCounts = new HashMap<>();
        for (PaymentAttempt attempt : paymentAttemptRepository.findAllByOrderByCreatedAtDesc()) {
            attemptCounts.merge(attempt.getBooking().getId(), 1L, Long::sum);
            if (attempt.getStatus() == PaymentStatus.FAILED) {
                failedCounts.merge(attempt.getBooking().getId(), 1L, Long::sum);
            }
        }

        Map<Long, BigDecimal> refundedByBooking =
                refundedTotals(cancellationRepository.findByBookingIds(byId.keySet()));

        List<AdminPaymentRowDto> rows = new ArrayList<>(payments.size());
        for (Payment payment : payments) {
            Booking booking = byId.get(payment.getBooking().getId());
            if (booking == null) {
                continue;
            }
            BigDecimal refunded = refundedByBooking.getOrDefault(booking.getId(), ZERO);
            rows.add(AdminPaymentRowDto.builder()
                    .paymentId(payment.getId())
                    .bookingId(booking.getId())
                    .bookingReference(booking.getBookingReference())
                    .passengerName(booking.getUser().getFullName())
                    .trainName(booking.getTrain().getName())
                    .trainNumber(booking.getTrain().getTrainNumber())
                    .sourceStationName(booking.getSourceStation().getName())
                    .destinationStationName(booking.getDestinationStation().getName())
                    .journeyDate(booking.getJourneyDate())
                    .bookingStatus(booking.getStatus().name())
                    .method(payment.getMethod())
                    .amount(payment.getAmount())
                    .status(payment.getStatus().name())
                    .transactionReference(payment.getTransactionReference())
                    .paidAt(payment.getCreatedAt())
                    .attemptCount(attemptCounts.getOrDefault(booking.getId(), 0L).intValue())
                    .failedAttemptCount(failedCounts.getOrDefault(booking.getId(), 0L).intValue())
                    .refundedAmount(refunded)
                    .netCollected(money(payment.getAmount().subtract(refunded)))
                    .build());
        }
        rows.sort(Comparator.comparing(AdminPaymentRowDto::getPaidAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return rows;
    }

    // ------------------------------------------------------------------ Phase 10: paged admin reads

    /** Largest page an operator may request. A bigger number is a typo, not an intent. */
    private static final int MAX_PAGE_SIZE = 100;

    /**
     * The settled-payment ledger, one page at a time.
     * <p>
     * The page is chosen, ordered and searched in the database; only that page's attempt counts and
     * refund totals are then fetched, in two grouped queries. The previous version materialised every
     * payment, attempt and refund to render one screen, which is the definition of a query that gets
     * slower as the business succeeds.
     */
    @Transactional(readOnly = true)
    public PageResponse<AdminPaymentRowDto> getAdminPaymentsPage(int page, int size, String search) {
        Pageable pageable = PageRequest.of(clampPage(page), clampSize(size),
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id")));
        Page<Payment> payments = paymentRepository.search(likePattern(search), pageable);

        List<Long> bookingIds = payments.getContent().stream()
                .map(payment -> payment.getBooking().getId())
                .toList();
        Map<Long, long[]> attempts = summariseAttempts(bookingIds);
        Map<Long, BigDecimal> refunded = bookingIds.isEmpty()
                ? Map.of()
                : refundedTotals(cancellationRepository.findByBookingIds(bookingIds));

        return PageResponse.of(payments, payment -> toAdminPaymentRow(payment, attempts, refunded));
    }

    /** The refund ledger, one page at a time, newest first. */
    @Transactional(readOnly = true)
    public PageResponse<RefundRecordDto> getAllRefundsPage(int page, int size, String search) {
        Pageable pageable = PageRequest.of(clampPage(page), clampSize(size),
                Sort.by(Sort.Direction.DESC, "cancelledAt").and(Sort.by(Sort.Direction.DESC, "id")));
        Page<Cancellation> cancellations = cancellationRepository.search(likePattern(search), pageable);
        return PageResponse.of(cancellations, this::toRefundRecord);
    }

    private AdminPaymentRowDto toAdminPaymentRow(Payment payment, Map<Long, long[]> attempts,
                                                 Map<Long, BigDecimal> refundedByBooking) {
        Booking booking = payment.getBooking();
        long[] counts = attempts.getOrDefault(booking.getId(), EMPTY_COUNTS);
        BigDecimal refunded = refundedByBooking.getOrDefault(booking.getId(), ZERO);
        return AdminPaymentRowDto.builder()
                .paymentId(payment.getId())
                .bookingId(booking.getId())
                .bookingReference(booking.getBookingReference())
                .passengerName(booking.getUser().getFullName())
                .trainName(booking.getTrain().getName())
                .trainNumber(booking.getTrain().getTrainNumber())
                .sourceStationName(booking.getSourceStation().getName())
                .destinationStationName(booking.getDestinationStation().getName())
                .journeyDate(booking.getJourneyDate())
                .bookingStatus(booking.getStatus().name())
                .method(payment.getMethod())
                .amount(payment.getAmount())
                .status(payment.getStatus().name())
                .transactionReference(payment.getTransactionReference())
                .paidAt(payment.getCreatedAt())
                .attemptCount((int) counts[0])
                .failedAttemptCount((int) counts[1])
                .refundedAmount(refunded)
                .netCollected(money(payment.getAmount().subtract(refunded)))
                .build();
    }

    private static final long[] EMPTY_COUNTS = {0L, 0L};

    private Map<Long, long[]> summariseAttempts(Collection<Long> bookingIds) {
        if (bookingIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, long[]> counts = new HashMap<>();
        for (Object[] row : paymentAttemptRepository.summariseByBookingIds(bookingIds)) {
            counts.put((Long) row[0],
                    new long[]{((Number) row[1]).longValue(), ((Number) row[2]).longValue()});
        }
        return counts;
    }

    /**
     * A case-insensitive contains pattern, or null for "no filter".
     * <p>
     * Normalising in the service (rather than trusting the browser to send a wildcarded string) is
     * what lets the same query serve a search box and an API caller.
     */
    private static String likePattern(String search) {
        if (search == null || search.isBlank()) {
            return null;
        }
        return "%" + search.trim().toLowerCase() + "%";
    }

    private static int clampPage(int page) {
        return Math.max(0, page);
    }

    private static int clampSize(int size) {
        if (size <= 0) {
            return 20;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }

    /**
     * Cross-checks the ledger against the bookings and reports every disagreement.
     * <p>
     * Each check compares facts written by <i>different</i> code paths, so a finding means something.
     * A missing finding where there is genuinely nothing wrong is the only acceptable outcome; the
     * report never claims to be reconciled on the basis of having read nothing.
     */
    @Transactional(readOnly = true)
    public ReconciliationReportDto reconcile() {
        List<Booking> bookings = bookingRepository.findAllByOrderByCreatedAtDesc();
        Map<Long, Payment> paymentsByBooking = new HashMap<>();
        for (Payment payment : paymentRepository.findAll()) {
            paymentsByBooking.put(payment.getBooking().getId(), payment);
        }
        Map<Long, List<PaymentAttempt>> attemptsByBooking =
                paymentAttemptRepository.findAllByOrderByCreatedAtDesc().stream()
                        .collect(Collectors.groupingBy(a -> a.getBooking().getId()));
        Map<Long, List<Cancellation>> cancellationsByBooking =
                cancellationRepository.findByBookingIds(
                                bookings.stream().map(Booking::getId).toList()).stream()
                        .collect(Collectors.groupingBy(c -> c.getBooking().getId()));

        List<ReconciliationIssueDto> issues = new ArrayList<>();
        BigDecimal totalCollected = ZERO;
        BigDecimal totalRefunded = ZERO;
        BigDecimal totalFailedAttempts = ZERO;
        long settledPayments = 0;
        long failedPayments = 0;
        long paidBookings = 0;

        for (Booking booking : bookings) {
            Payment payment = paymentsByBooking.get(booking.getId());
            List<PaymentAttempt> attempts = attemptsByBooking.getOrDefault(booking.getId(), List.of());
            List<Cancellation> cancellations = cancellationsByBooking.getOrDefault(booking.getId(), List.of());
            BigDecimal refunded = money(cancellations.stream()
                    .map(Cancellation::getRefundAmount)
                    .reduce(ZERO, BigDecimal::add));

            boolean bookingHoldsSeats = booking.getStatus() == BookingStatus.CONFIRMED
                    || booking.getStatus() == BookingStatus.RAC
                    || booking.getStatus() == BookingStatus.WAITLISTED;

            if (payment != null && payment.getStatus() == PaymentStatus.SUCCESS) {
                settledPayments++;
                totalCollected = money(totalCollected.add(payment.getAmount()));
            }
            if (payment != null && payment.getStatus() == PaymentStatus.FAILED) {
                failedPayments++;
            }
            if (bookingHoldsSeats) {
                paidBookings++;
            }
            for (PaymentAttempt attempt : attempts) {
                if (attempt.getStatus() == PaymentStatus.FAILED) {
                    totalFailedAttempts = money(totalFailedAttempts.add(attempt.getAmount()));
                }
            }
            totalRefunded = money(totalRefunded.add(refunded));

            // 1. Seats were issued but no money ever arrived.
            if (bookingHoldsSeats && (payment == null || payment.getStatus() != PaymentStatus.SUCCESS)) {
                issues.add(issue(booking, "PAID_STATUS_NO_PAYMENT",
                        "The booking is " + booking.getStatus() + " but has no successful payment on record. "
                                + "These seats were given away.",
                        ReconciliationIssueDto.SEVERITY_CRITICAL, null));
            }

            // 2. Money arrived but the booking is not in a paid state.
            if (payment != null && payment.getStatus() == PaymentStatus.SUCCESS
                    && !bookingHoldsSeats && booking.getStatus() != BookingStatus.CANCELLED) {
                issues.add(issue(booking, "PAID_WITHOUT_TRAVEL_STATE",
                        "Payment of " + payment.getAmount() + " succeeded but the booking status is "
                                + booking.getStatus() + ".",
                        ReconciliationIssueDto.SEVERITY_CRITICAL, payment.getAmount()));
            }

            // 3. The charged amount and the booked total disagree.
            if (payment != null && payment.getStatus() == PaymentStatus.SUCCESS
                    && money(payment.getAmount()).compareTo(money(booking.getTotalFare())) != 0) {
                issues.add(issue(booking, "AMOUNT_MISMATCH",
                        "Charged " + payment.getAmount() + " but the booking totals "
                                + booking.getTotalFare() + ".",
                        ReconciliationIssueDto.SEVERITY_WARNING, payment.getAmount()));
            }

            // 4. Refunds recorded against a booking that was never paid for.
            if (refunded.signum() > 0 && (payment == null || payment.getStatus() != PaymentStatus.SUCCESS)) {
                issues.add(issue(booking, "REFUND_WITHOUT_PAYMENT",
                        refunded + " has been refunded but no successful payment exists for this booking.",
                        ReconciliationIssueDto.SEVERITY_CRITICAL, refunded));
            }

            // 5. More money went back than came in. The worst possible reconciliation finding.
            if (payment != null && refunded.compareTo(payment.getAmount()) > 0) {
                issues.add(issue(booking, "REFUND_EXCEEDS_PAYMENT",
                        "Refunded " + refunded + " against a payment of only " + payment.getAmount() + ".",
                        ReconciliationIssueDto.SEVERITY_CRITICAL, refunded));
            }

            // 6. A refund that never settled, or that failed and needs a human.
            for (Cancellation cancellation : cancellations) {
                if (cancellation.getRefundStatus() == RefundStatus.PENDING) {
                    issues.add(issue(booking, "REFUND_PENDING",
                            "A refund of " + cancellation.getRefundAmount() + " is still pending.",
                            ReconciliationIssueDto.SEVERITY_WARNING, cancellation.getRefundAmount()));
                }
                if (cancellation.getRefundStatus() == RefundStatus.FAILED) {
                    issues.add(issue(booking, "REFUND_FAILED",
                            "A refund of " + cancellation.getRefundAmount() + " failed and needs follow-up.",
                            ReconciliationIssueDto.SEVERITY_CRITICAL, cancellation.getRefundAmount()));
                }
            }

            // 7. A settled payment with no ledger entry: the history is incomplete, so the
            //    passenger's own payment page would be missing a real transaction.
            if (payment != null && attempts.isEmpty()) {
                issues.add(issue(booking, "MISSING_LEDGER_ENTRY",
                        "A settled payment of " + payment.getAmount() + " has no entry in the payment ledger.",
                        ReconciliationIssueDto.SEVERITY_WARNING, payment.getAmount()));
            }

            // 8. An unpaid hold that outlived its payment window and was not swept.
            if (booking.getStatus() == BookingStatus.PAYMENT_PENDING
                    && booking.getPaymentDeadline() != null
                    && booking.getPaymentDeadline().isBefore(LocalDateTime.now())) {
                issues.add(issue(booking, "OVERDUE_PAYMENT_PENDING",
                        "The payment window closed at " + booking.getPaymentDeadline()
                                + " but the booking is still PAYMENT_PENDING and still holding a seat.",
                        ReconciliationIssueDto.SEVERITY_WARNING, null));
            }

            // 9. Some but not all passengers cancelled. Informational: a real and expected state
            //    after a partial cancellation, but worth showing so the operator knows the reason.
            //    Counted from the passenger rows rather than from the cancellation rows, because a
            //    staged cancellation writes one per-passenger row per event plus a final
            //    "All passengers" row with no passenger, which would undercount a booking whose
            //    passengers have all in fact been cancelled.
            long cancelledPassengers = booking.getPassengers().stream()
                    .filter(p -> p.getPassengerStatus() == PassengerStatus.CANCELLED)
                    .count();
            if (cancelledPassengers > 0 && cancelledPassengers < booking.getPassengers().size()) {
                issues.add(issue(booking, "PARTIALLY_REFUNDED",
                        cancelledPassengers + " of " + booking.getPassengers().size()
                                + " passengers have been cancelled and refunded.",
                        ReconciliationIssueDto.SEVERITY_INFO, refunded));
            }
        }

        boolean clean = issues.stream()
                .noneMatch(i -> ReconciliationIssueDto.SEVERITY_CRITICAL.equals(i.getSeverity())
                        || ReconciliationIssueDto.SEVERITY_WARNING.equals(i.getSeverity()));
        issues.sort(Comparator.comparingInt(Severity::rankOf)
                .thenComparing(ReconciliationIssueDto::getBookingReference,
                        Comparator.nullsLast(Comparator.naturalOrder())));

        return ReconciliationReportDto.builder()
                .generatedAt(LocalDateTime.now())
                .totalCollected(totalCollected)
                .totalRefunded(totalRefunded)
                .totalFailedAttempts(totalFailedAttempts)
                .netCollected(money(totalCollected.subtract(totalRefunded)))
                .paymentAttemptCount(paymentAttemptRepository.count())
                .settledPaymentCount(settledPayments)
                .failedPaymentCount(failedPayments)
                .refundCount(cancellationRepository.count())
                .pendingRefundCount(cancellationRepository.countByRefundStatus(RefundStatus.PENDING))
                .failedRefundCount(cancellationRepository.countByRefundStatus(RefundStatus.FAILED))
                .bookingCount(bookings.size())
                .paidBookingCount(paidBookings)
                .reconciled(clean)
                .issues(issues)
                .build();
    }

    // ------------------------------------------------------------------ helpers

    private ReconciliationIssueDto issue(Booking booking, String code, String message,
                                        String severity, BigDecimal amount) {
        return ReconciliationIssueDto.builder()
                .bookingId(booking.getId())
                .bookingReference(booking.getBookingReference())
                .code(code)
                .message(message)
                .severity(severity)
                .amount(amount)
                .build();
    }

    private Map<Long, BigDecimal> refundedTotals(List<Cancellation> cancellations) {
        Map<Long, BigDecimal> totals = new HashMap<>();
        for (Cancellation cancellation : cancellations) {
            totals.merge(cancellation.getBooking().getId(), money(cancellation.getRefundAmount()),
                    (a, b) -> money(a.add(b)));
        }
        return totals;
    }

    private RefundRecordDto toRefundRecord(Cancellation cancellation) {
        return RefundRecordDto.builder()
                .id(cancellation.getId())
                .bookingId(String.valueOf(cancellation.getBooking().getId()))
                .bookingReference(cancellation.getBooking().getBookingReference())
                .refundReference(cancellation.getRefundReference())
                .bookingPassengerId(cancellation.getBookingPassenger() == null
                        ? null : cancellation.getBookingPassenger().getId())
                .passengerName(cancellation.getBookingPassenger() == null
                        ? "All passengers"
                        : cancellation.getBookingPassenger().getFullName())
                .reason(cancellation.getReason())
                .refundAmount(cancellation.getRefundAmount())
                .refundStatus(cancellation.getRefundStatus() == null
                        ? null : cancellation.getRefundStatus().name())
                .scope(cancellation.getScope() == null ? null : cancellation.getScope().name())
                .fareRefunded(cancellation.getFareRefunded())
                .gstRefunded(cancellation.getGstRefunded())
                .platformFeeRefunded(cancellation.getPlatformFeeRefunded())
                .cancellationCharge(cancellation.getCancellationCharge())
                .refundPercent(cancellation.getRefundPercent() == null
                        ? null : cancellation.getRefundPercent().intValue())
                .daysBeforeJourney(cancellation.getDaysBeforeJourney())
                .cancelledAt(cancellation.getCancelledAt())
                .processedAt(cancellation.getProcessedAt())
                .build();
    }

    private static BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Orders findings by how much they need a human. Naming the rank in one place keeps the
     * comparator readable and stops "critical at the bottom" from ever happening again.
     */
    private static final class Severity {
        private Severity() {
        }

        static int rankOf(ReconciliationIssueDto issue) {
            return switch (issue.getSeverity() == null ? "" : issue.getSeverity()) {
                case ReconciliationIssueDto.SEVERITY_CRITICAL -> 0;
                case ReconciliationIssueDto.SEVERITY_WARNING -> 1;
                default -> 2;
            };
        }
    }
}

package com.yatrarail.service;

import com.yatrarail.dto.admin.OperationsReportDto;
import com.yatrarail.entity.BookingStatus;
import com.yatrarail.entity.RefundStatus;
import com.yatrarail.repository.BookingRepository;
import com.yatrarail.repository.CancellationRepository;
import com.yatrarail.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Phase 7 operations report.
 * <p>
 * Every figure is grouped in SQL rather than by loading bookings and streaming them in Java, because
 * the range is chosen by the operator and may well be a year. The revenue statuses are the same set
 * the dashboard already uses, so the two screens can never report different money for the same
 * bookings.
 */
@Service
@RequiredArgsConstructor
public class ReportService {

    /** Statuses whose fare counts as revenue. Mirrors the dashboard's rule exactly. */
    private static final EnumSet<BookingStatus> REVENUE_STATUSES =
            EnumSet.of(BookingStatus.CONFIRMED, BookingStatus.RAC, BookingStatus.WAITLISTED);

    /** How many trains/routes to list. A longer list is a CSV export, which is Phase 10. */
    private static final int TOP_N = 10;

    private final BookingRepository bookingRepository;
    private final CancellationRepository cancellationRepository;
    private final UserRepository userRepository;

    /**
     * @param from inclusive journey date, or null for "from the beginning"
     * @param to   inclusive journey date, or null for "up to the horizon"
     */
    @Transactional(readOnly = true)
    public OperationsReportDto report(LocalDate from, LocalDate to) {
        LocalDate effectiveFrom = from;
        LocalDate effectiveTo = to;
        if (effectiveFrom != null && effectiveTo != null && effectiveFrom.isAfter(effectiveTo)) {
            // A reversed range is a typo, not an empty report: swapping is friendlier than returning
            // a wall of zeroes that looks like a dead system.
            LocalDate swap = effectiveFrom;
            effectiveFrom = effectiveTo;
            effectiveTo = swap;
        }

        // An aggregate with no GROUP BY always yields exactly one row, but the row is still wrapped in
        // a list by the persistence provider, so it is read as such rather than assumed to be the
        // array itself.
        List<Object[]> totals = bookingRepository.summariseTotals(effectiveFrom, effectiveTo, REVENUE_STATUSES);
        Object[] t = totals.isEmpty() ? new Object[10] : totals.get(0);
        long bookings = asLong(t, 0);
        long confirmed = asLong(t, 1);
        long rac = asLong(t, 2);
        long waitlisted = asLong(t, 3);
        long cancelled = asLong(t, 4);
        long expired = asLong(t, 5);
        long paymentFailed = asLong(t, 6);
        long paymentPending = asLong(t, 7);
        BigDecimal grossFare = asDecimal(t, 8);
        BigDecimal platformFees = asDecimal(t, 9);

        Map<String, Long> byStatus = new LinkedHashMap<>();
        bookingRepository.summariseByStatus(effectiveFrom, effectiveTo)
                .forEach(row -> byStatus.put(String.valueOf(row[0]), asLong(row, 1)));

        List<OperationsReportDto.DailyStat> daily = bookingRepository
                .summariseByJourneyDate(effectiveFrom, effectiveTo, REVENUE_STATUSES)
                .stream()
                .map(row -> OperationsReportDto.DailyStat.builder()
                        .journeyDate((LocalDate) row[0])
                        .bookings(asLong(row, 1))
                        .revenue(asDecimal(row, 2))
                        .cancelled(asLong(row, 3))
                        .build())
                .toList();

        List<OperationsReportDto.TrainStat> byTrain = bookingRepository
                .summariseByTrain(effectiveFrom, effectiveTo, REVENUE_STATUSES)
                .stream()
                .limit(TOP_N)
                .map(row -> OperationsReportDto.TrainStat.builder()
                        .trainId(asLong(row, 0))
                        .trainNumber((String) row[1])
                        .trainName((String) row[2])
                        .bookings(asLong(row, 3))
                        .revenue(asDecimal(row, 4))
                        .build())
                .toList();

        List<com.yatrarail.dto.admin.RouteStat> byRoute = bookingRepository
                .summariseByRoute(effectiveFrom, effectiveTo)
                .stream()
                .limit(TOP_N)
                .map(row -> com.yatrarail.dto.admin.RouteStat.builder()
                        .route(row[0] + " \u2192 " + row[1])
                        .bookingCount(asLong(row, 2))
                        .build())
                .toList();

        return OperationsReportDto.builder()
                .from(effectiveFrom)
                .to(effectiveTo)
                .totalBookings(bookings)
                .confirmedBookings(confirmed)
                .racBookings(rac)
                .waitlistedBookings(waitlisted)
                .cancelledBookings(cancelled)
                .expiredBookings(expired)
                .paymentFailedBookings(paymentFailed)
                .paymentPendingBookings(paymentPending)
                .grossFare(grossFare)
                .platformFees(platformFees)
                .passengerCount(bookingRepository.countPassengers(effectiveFrom, effectiveTo))
                .totalUsers(userRepository.count())
                .byStatus(byStatus)
                .daily(daily)
                .byTrain(byTrain)
                .byRoute(byRoute)
                .build();
    }

    /** Refund totals, kept beside the report rather than folded into it. */
    @Transactional(readOnly = true)
    public RefundSummary refundSummary() {
        List<RefundStatus> statuses = List.of(RefundStatus.values());
        Map<String, Long> byStatus = new LinkedHashMap<>();
        statuses.forEach(s -> byStatus.put(s.name(), cancellationRepository.countByRefundStatus(s)));
        return RefundSummary.builder()
                .byStatus(byStatus)
                .refundedTotal(cancellationRepository.sumRefundAmountByStatus(RefundStatus.PROCESSED))
                .pendingTotal(cancellationRepository.sumRefundAmountByStatus(RefundStatus.PENDING))
                .failedTotal(cancellationRepository.sumRefundAmountByStatus(RefundStatus.FAILED))
                .build();
    }

    /** Refund money by state. A pending refund is a liability, so it is never netted off. */
    @lombok.Data
    @lombok.Builder
    public static class RefundSummary {
        private Map<String, Long> byStatus;
        private BigDecimal refundedTotal;
        private BigDecimal pendingTotal;
        private BigDecimal failedTotal;
    }

    // Aggregations return null for an empty range, and sum() over no rows is null rather than 0.
    private long asLong(Object[] row, int index) {
        return row == null || row[index] == null ? 0L : ((Number) row[index]).longValue();
    }

    private BigDecimal asDecimal(Object[] row, int index) {
        return row == null || row[index] == null ? BigDecimal.ZERO : (BigDecimal) row[index];
    }
}

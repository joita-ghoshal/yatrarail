package com.yatrarail.dto.admin;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * The Phase 7 operations report: one request, one screen, every number an operator is asked for.
 * <p>
 * The date range is echoed back in the response because the report is useless without knowing what
 * window produced it — a figure of "₹42,000 collected" is meaningless unless the reader can see
 * whether it covers a week or a year, and the window is chosen in the UI rather than fixed.
 */
@Data
@Builder
public class OperationsReportDto {

    private LocalDate from;
    private LocalDate to;

    /** Bookings created for journeys in the range. */
    private long totalBookings;
    private long confirmedBookings;
    private long cancelledBookings;
    private long waitlistedBookings;
    private long racBookings;
    private long expiredBookings;
    private long paymentFailedBookings;
    private long paymentPendingBookings;

    /** Fares collected for bookings that hold or held inventory, over the range. */
    private BigDecimal grossFare;
    private BigDecimal platformFees;

    private long passengerCount;

    /** Total registered accounts. A whole-system figure, not range-scoped. */
    private long totalUsers;

    /** Status split, keyed by the enum name, for the summary table. */
    private Map<String, Long> byStatus;

    /** One entry per journey date with bookings, in ascending date order. */
    private List<DailyStat> daily;

    /** Busiest trains over the range, ordered by booking count. */
    private List<TrainStat> byTrain;

    /** Busiest origin → destination pairs over the range, ordered by booking count. */
    private List<RouteStat> byRoute;

    @Data
    @Builder
    public static class DailyStat {
        private LocalDate journeyDate;
        private long bookings;
        private long cancelled;
        private BigDecimal revenue;
    }

    @Data
    @Builder
    public static class TrainStat {
        private Long trainId;
        private String trainNumber;
        private String trainName;
        private long bookings;
        private BigDecimal revenue;
    }
}

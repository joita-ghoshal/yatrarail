package com.yatrarail.dto.admin;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DashboardStatsDto {
    private long totalUsers;
    private long totalTrains;
    private long totalStations;
    private long totalBookings;
    private long confirmedBookings;
    private long cancelledBookings;
    private long waitlistedBookings;
    /** Bookings holding a berth on RAC rather than their own seat. */
    private long racBookings;
    /** Unpaid bookings whose seat hold lapsed. */
    private long expiredBookings;
    private long activeTrains;
    private BigDecimal totalDemoRevenue;
    private Map<String, Long> bookingsByClass;
    private List<MonthlyStat> monthlyBookings;
    private List<RouteStat> popularRoutes;
}

package com.yatrarail.service;

import com.yatrarail.dto.admin.DashboardStatsDto;
import com.yatrarail.dto.admin.MonthlyStat;
import com.yatrarail.dto.admin.RouteStat;
import com.yatrarail.entity.*;
import com.yatrarail.exception.ResourceNotFoundException;
import com.yatrarail.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.format.TextStyle;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AdminService {

    private final UserRepository userRepository;
    private final TrainRepository trainRepository;
    private final StationRepository stationRepository;
    private final BookingRepository bookingRepository;
    private final PaymentRepository paymentRepository;

    @Transactional(readOnly = true)
    public List<User> getAllUsers() {
        return userRepository.findAll();
    }

    @Transactional
    public User setUserActive(Long userId, boolean active) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        user.setActive(active);
        return userRepository.save(user);
    }

    @Transactional(readOnly = true)
    public DashboardStatsDto getDashboardStats() {
        List<Booking> allBookings = bookingRepository.findAll();
        long confirmed = allBookings.stream().filter(b -> b.getStatus() == BookingStatus.CONFIRMED).count();
        long cancelled = allBookings.stream().filter(b -> b.getStatus() == BookingStatus.CANCELLED).count();
        long waitlisted = allBookings.stream().filter(b -> b.getStatus() == BookingStatus.WAITLISTED).count();
        long rac = allBookings.stream().filter(b -> b.getStatus() == BookingStatus.RAC).count();
        long expired = allBookings.stream().filter(b -> b.getStatus() == BookingStatus.EXPIRED).count();

        BigDecimal revenue = allBookings.stream()
                .filter(b -> b.getStatus() == BookingStatus.CONFIRMED
                        || b.getStatus() == BookingStatus.WAITLISTED
                        || b.getStatus() == BookingStatus.RAC)
                .map(Booking::getTotalFare)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<String, Long> byClass = allBookings.stream()
                .filter(b -> b.getStatus() != BookingStatus.PAYMENT_PENDING
                        && b.getStatus() != BookingStatus.PAYMENT_FAILED
                        && b.getStatus() != BookingStatus.EXPIRED)
                .collect(Collectors.groupingBy(b -> b.getCoach().getSeatClass().getLabel(), Collectors.counting()));

        Map<String, long[]> monthly = new LinkedHashMap<>();
        allBookings.forEach(b -> {
            String key = b.getCreatedAt().getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " " + b.getCreatedAt().getYear();
            monthly.putIfAbsent(key, new long[2]);
            if (b.getStatus() == BookingStatus.CONFIRMED
                    || b.getStatus() == BookingStatus.WAITLISTED
                    || b.getStatus() == BookingStatus.RAC) {
                monthly.get(key)[0]++;
            }
            if (b.getStatus() == BookingStatus.CANCELLED) {
                monthly.get(key)[1]++;
            }
        });
        List<MonthlyStat> monthlyStats = monthly.entrySet().stream()
                .map(e -> MonthlyStat.builder().month(e.getKey()).confirmed(e.getValue()[0]).cancelled(e.getValue()[1]).build())
                .collect(Collectors.toList());

        Map<String, Long> routeCounts = allBookings.stream()
                .collect(Collectors.groupingBy(
                        b -> b.getSourceStation().getCode() + " \u2192 " + b.getDestinationStation().getCode(),
                        Collectors.counting()));
        List<RouteStat> popularRoutes = routeCounts.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(5)
                .map(e -> RouteStat.builder().route(e.getKey()).bookingCount(e.getValue()).build())
                .collect(Collectors.toList());

        return DashboardStatsDto.builder()
                .totalUsers(userRepository.count())
                .totalTrains(trainRepository.count())
                .totalStations(stationRepository.count())
                .totalBookings(allBookings.size())
                .confirmedBookings(confirmed)
                .cancelledBookings(cancelled)
                .waitlistedBookings(waitlisted)
                .racBookings(rac)
                .expiredBookings(expired)
                .activeTrains(trainRepository.countByActiveTrue())
                .totalDemoRevenue(revenue)
                .bookingsByClass(byClass)
                .monthlyBookings(monthlyStats)
                .popularRoutes(popularRoutes)
                .build();
    }
}
package com.yatrarail.service;

import com.yatrarail.config.BookingProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * Releases seat holds whose payment window elapsed.
 * <p>
 * Before Phase 2 a {@code PAYMENT_PENDING} booking never expired: the seat was allocated at creation
 * time, nothing released it, and a passenger could abandon the flow and permanently squat on
 * inventory. This job closes that loop.
 * <p>
 * Each booking is expired through a separate transactional call so one failure cannot roll back or
 * strand the rest of the batch. Because a hold's deadline is enforced on read as well
 * ({@code expiresAt > now}), inventory is never blocked even between job runs.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "yatrarail.booking", name = "expiry-job-enabled",
        havingValue = "true", matchIfMissing = true)
public class PendingBookingExpiryJob {

    private final SeatAllocationService seatAllocationService;
    private final BookingProperties bookingProperties;

    @Scheduled(cron = "${yatrarail.booking.expiry-cron:0 * * * * *}")
    public void releaseExpiredHolds() {
        Set<Long> overdue = seatAllocationService.findOverduePendingBookingIds(LocalDateTime.now());
        if (overdue.isEmpty()) {
            return;
        }
        int expired = 0;
        for (Long bookingId : overdue) {
            try {
                if (seatAllocationService.expireBooking(bookingId)) {
                    expired++;
                }
            } catch (RuntimeException ex) {
                log.warn("Could not expire booking {}: {}", bookingId, ex.getMessage());
            }
        }
        if (expired > 0) {
            log.info("Released {} expired unpaid booking(s) (hold window {} min)",
                    expired, bookingProperties.getHoldMinutes());
        }
    }
}

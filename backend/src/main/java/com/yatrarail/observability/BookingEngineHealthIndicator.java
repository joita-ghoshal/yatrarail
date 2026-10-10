package com.yatrarail.observability;

import com.yatrarail.entity.ReservationStatus;
import com.yatrarail.repository.SeatReservationRepository;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Phase 10 — a domain-aware readiness signal.
 * <p>
 * A database ping proves the connection is up; it says nothing about whether seats are being held
 * correctly. This indicator reads the reservation ledger and reports it:
 * <ul>
 *   <li><b>liveHoldsToday</b> — seat holds and confirmed reservations on today's date. A number that
 *       only climbs while bookings fall is the shape of a leak.</li>
 *   <li><b>overdueHolds</b> — holds past their payment deadline. In normal operation the expiry job
 *       drains these within a scheduling interval, so a persistent value means the job is stuck.</li>
 * </ul>
 * It is deliberately a <i>reported</i> detail rather than a hard DOWN: overdue holds degrade
 * inventory, they do not stop the site serving. A database failure, by contrast, throws and the
 * indicator goes DOWN — which is exactly when a load balancer should pull the node.
 */
@Component
public class BookingEngineHealthIndicator implements HealthIndicator {

    private final SeatReservationRepository reservations;

    public BookingEngineHealthIndicator(SeatReservationRepository reservations) {
        this.reservations = reservations;
    }

    @Override
    public Health health() {
        long held = reservations.countByJourneyDateAndStatus(LocalDate.now(), ReservationStatus.HELD);
        long confirmed = reservations.countByJourneyDateAndStatus(LocalDate.now(), ReservationStatus.CONFIRMED);
        long overdue = reservations.countByStatusAndExpiresAtBefore(ReservationStatus.HELD, LocalDateTime.now());

        return Health.up()
                .withDetail("liveHoldsToday", held + confirmed)
                .withDetail("confirmedToday", confirmed)
                .withDetail("overdueHolds", overdue)
                .withDetail("expiryJobHealthy", overdue == 0)
                .build();
    }
}

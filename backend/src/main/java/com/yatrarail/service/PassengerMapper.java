package com.yatrarail.service;

import com.yatrarail.dto.booking.PassengerResultDto;
import com.yatrarail.entity.Booking;
import com.yatrarail.entity.BookingPassenger;
import com.yatrarail.entity.PassengerStatus;
import com.yatrarail.entity.QuotaType;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Turns {@link BookingPassenger} rows into the DTO a passenger is shown.
 * <p>
 * Phase 5 needs the same passenger line in three places - the booking details page, the printable
 * ticket and the refund preview - and each of those needs the seat, the quota actually granted and
 * the net fare actually charged. Duplicating that mapping three times is how the three views drift
 * apart, so it lives here once.
 */
@Component
public class PassengerMapper {

    public List<PassengerResultDto> describe(Booking booking) {
        if (booking == null || booking.getPassengers() == null) {
            return List.of();
        }
        return booking.getPassengers().stream().map(this::describe).toList();
    }

    public PassengerResultDto describe(BookingPassenger passenger) {
        QuotaType quota = passenger.getQuota() == null ? QuotaType.GENERAL : passenger.getQuota();
        PassengerStatus status = passenger.getPassengerStatus();
        boolean queued = status == PassengerStatus.RAC || status == PassengerStatus.WAITLISTED;
        return PassengerResultDto.builder()
                .id(passenger.getId())
                .fullName(passenger.getFullName())
                .age(passenger.getAge())
                .gender(passenger.getGender())
                .berthPreference(passenger.getBerthPreference())
                .coachCode(passenger.getSeat() != null ? passenger.getSeat().getCoach().getCoachCode() : null)
                .seatNumber(passenger.getSeat() != null ? passenger.getSeat().getSeatNumber() : null)
                .berthType(passenger.getSeat() != null ? passenger.getSeat().getBerthType() : null)
                .passengerStatus(status == null ? null : status.name())
                // A queue position only means something while the passenger is actually queued;
                // reporting a stale one after promotion would be misleading.
                .waitlistPosition(queued ? passenger.getWaitlistPosition() : null)
                .rac(status == PassengerStatus.RAC)
                .quota(quota.name())
                .quotaLabel(quota.getLabel())
                .quotaDiscountPercent(passenger.getQuotaDiscountPercent())
                .fareAmount(passenger.getFareAmount())
                .quotaNote(passenger.getQuotaNote())
                .build();
    }
}

package com.yatrarail.dto.staff;

import com.yatrarail.entity.Booking;
import com.yatrarail.entity.BookingPassenger;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;

import java.util.List;

/**
 * Staff passenger-manifest DTO. Keeps the nested shape the staff console expects:
 * booking-level ({@code bookingReference}, {@code status}, {@code coach.seatClass}) and
 * passenger-level ({@code passengers[].seat.coach.coachCode}, {@code passengers[].seat.seatNumber}).
 */
@Data
@Builder
public class StaffPassengerDto {
    private Long id;
    private String bookingReference;
    private String status;
    @Getter
    private CoachRef coach;
    private List<Passenger> passengers;

    public static StaffPassengerDto from(Booking booking) {
        return StaffPassengerDto.builder()
                .id(booking.getId())
                .bookingReference(booking.getBookingReference())
                .status(booking.getStatus().name())
                .coach(CoachRef.from(booking.getCoach()))
                .passengers(booking.getPassengers().stream().map(Passenger::from).toList())
                .build();
    }

    @Data
    @Builder
    public static class CoachRef {
        private String coachCode;
        private String seatClass;

        public static CoachRef from(com.yatrarail.entity.Coach coach) {
            if (coach == null) {
                return null;
            }
            return CoachRef.builder()
                    .coachCode(coach.getCoachCode())
                    .seatClass(coach.getSeatClass() != null ? coach.getSeatClass().name() : null)
                    .build();
        }
    }

    @Data
    @Builder
    public static class Passenger {
        /** Phase 6: the gate needs this to mark check-in without a second call. */
        private Long id;
        private String fullName;
        private Integer age;
        private String gender;
        private String quota;
        private Boarding boarding;
        private SeatRef seat;

        public static Passenger from(BookingPassenger p) {
            return Passenger.builder()
                    .id(p.getId())
                    .fullName(p.getFullName())
                    .age(p.getAge())
                    .gender(p.getGender())
                    .quota(p.getQuota() != null ? p.getQuota().name() : null)
                    .boarding(Boarding.from(p))
                    .seat(SeatRef.from(p.getSeat()))
                    .build();
        }
    }

    /**
     * Gate check-in state for one passenger.
     * <p>
     * {@code boardingMarkedByName} travels with the state on purpose: when a conductor asks "who
     * marked this passenger as a no-show?", answering without a second round trip is the difference
     * between a usable audit trail and a shrug.
     */
    @Data
    @Builder
    public static class Boarding {
        private String status;
        private String markedByName;
        private java.time.LocalDateTime markedAt;

        public static Boarding from(BookingPassenger p) {
            if (p.getBoardingStatus() == null) {
                return null;
            }
            return Boarding.builder()
                    .status(p.getBoardingStatus().name())
                    .markedByName(p.getBoardingMarkedBy() != null ? p.getBoardingMarkedBy().getFullName() : null)
                    .markedAt(p.getBoardingMarkedAt())
                    .build();
        }
    }

    @Data
    @Builder
    public static class SeatRef {
        private Integer seatNumber;
        private CoachRef coach;

        public static SeatRef from(com.yatrarail.entity.Seat seat) {
            if (seat == null) {
                return null;
            }
            return SeatRef.builder()
                    .seatNumber(seat.getSeatNumber())
                    .coach(CoachRef.from(seat.getCoach()))
                    .build();
        }
    }
}
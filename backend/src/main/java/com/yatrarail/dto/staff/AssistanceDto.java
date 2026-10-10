package com.yatrarail.dto.staff;

import com.yatrarail.entity.AssistanceRequest;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Assistance-request DTO for the staff queue. Carries enough context to act on the request without
 * a second call: which passenger, which seat, on which train and date.
 */
@Data
@Builder
public class AssistanceDto {
    private Long id;
    private Long bookingPassengerId;
    private Long bookingId;
    private String bookingReference;
    private String passengerName;
    private String coachCode;
    private String seatNumber;
    private String trainName;
    private String trainNumber;
    private LocalDate journeyDate;
    private String category;
    private String status;
    private String details;
    private String assignedToName;
    private LocalDateTime raisedAt;
    private LocalDateTime resolvedAt;

    public static AssistanceDto from(AssistanceRequest request) {
        var booking = request.getBooking();
        var passenger = request.getBookingPassenger();
        var seat = passenger != null ? passenger.getSeat() : null;
        return AssistanceDto.builder()
                .id(request.getId())
                .bookingPassengerId(passenger != null ? passenger.getId() : null)
                .bookingId(booking != null ? booking.getId() : null)
                .bookingReference(booking != null ? booking.getBookingReference() : null)
                .passengerName(passenger != null ? passenger.getFullName() : null)
                .coachCode(seat != null && seat.getCoach() != null ? seat.getCoach().getCoachCode() : null)
                .seatNumber(seat != null ? String.valueOf(seat.getSeatNumber()) : null)
                .trainName(booking != null && booking.getTrain() != null ? booking.getTrain().getName() : null)
                .trainNumber(booking != null && booking.getTrain() != null ? booking.getTrain().getTrainNumber() : null)
                .journeyDate(booking != null ? booking.getJourneyDate() : null)
                .category(request.getCategory().name())
                .status(request.getStatus().name())
                .details(request.getDetails())
                .assignedToName(request.getAssignedTo() != null ? request.getAssignedTo().getFullName() : null)
                .raisedAt(request.getRaisedAt())
                .resolvedAt(request.getResolvedAt())
                .build();
    }
}
package com.yatrarail.dto.booking;

import com.yatrarail.dto.fare.FareBreakdownDto;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BookingResponseDto {
    private Long id;
    private String bookingReference;
    private String trainName;
    private String trainNumber;
    private String sourceStationName;
    private String destinationStationName;
    private LocalDate journeyDate;
    private String seatClass;
    private String status;
    private BigDecimal totalFare;
    /** Distance of the booked segment in km. */
    private Integer distanceKm;
    private BigDecimal farePerPassenger;
    /**
     * Phase 4 — the itemised fare, rebuilt from the snapshot stored on the booking. It is
     * deliberately not re-derived from the live tariff, so a later fare change can never alter
     * what an existing ticket says it cost.
     */
    private FareBreakdownDto fareBreakdown;
    /** When a PAYMENT_PENDING booking's seat hold lapses; null once paid. */
    private LocalDateTime paymentDeadline;
    private LocalDateTime createdAt;
    private List<PassengerResultDto> passengers;
    private PaymentDto payment;
}

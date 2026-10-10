package com.yatrarail.service;

import com.yatrarail.dto.staff.StaffDashboardDto;
import com.yatrarail.entity.AssistanceStatus;
import com.yatrarail.entity.BoardingStatus;
import com.yatrarail.entity.BookingStatus;
import com.yatrarail.entity.IncidentStatus;
import com.yatrarail.entity.PassengerStatus;
import com.yatrarail.repository.AssistanceRequestRepository;
import com.yatrarail.repository.BookingPassengerRepository;
import com.yatrarail.repository.BookingRepository;
import com.yatrarail.repository.TrainIncidentRepository;
import com.yatrarail.repository.TrainRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The numbers the staff portal opens on.
 * <p>
 * Every tally is counted in the database with a grouped query rather than by loading the day's
 * passengers into memory and counting in Java: a busy day is a few thousand rows, and the dashboard
 * is the page every conductor opens first thing, so it should not be the expensive one.
 */
@Service
@RequiredArgsConstructor
public class StaffDashboardService {

    private static final List<BookingStatus> TRAVELLING_BOOKINGS = List.of(
            BookingStatus.CONFIRMED, BookingStatus.RAC, BookingStatus.WAITLISTED);

    private static final List<PassengerStatus> TRAVELLING_PASSENGERS = List.of(
            PassengerStatus.CONFIRMED, PassengerStatus.RAC, PassengerStatus.WAITLISTED);

    private static final List<IncidentStatus> OPEN_INCIDENTS = List.of(
            IncidentStatus.OPEN, IncidentStatus.ACKNOWLEDGED);

    private static final List<AssistanceStatus> OPEN_ASSISTANCE = List.of(
            AssistanceStatus.OPEN, AssistanceStatus.ACKNOWLEDGED);

    private final TrainRepository trainRepository;
    private final BookingRepository bookingRepository;
    private final BookingPassengerRepository bookingPassengerRepository;
    private final TrainIncidentRepository incidentRepository;
    private final AssistanceRequestRepository assistanceRequestRepository;

    @Transactional(readOnly = true)
    public StaffDashboardDto getDashboard(LocalDate journeyDate) {
        Map<BoardingStatus, Long> boarding = new EnumMap<>(BoardingStatus.class);
        for (Object[] row : bookingPassengerRepository.countBoardingByStatusForDate(
                journeyDate, TRAVELLING_PASSENGERS)) {
            if (row[0] instanceof BoardingStatus status && row[1] instanceof Number n) {
                boarding.merge(status, n.longValue(), Long::sum);
            }
        }

        return StaffDashboardDto.builder()
                .journeyDate(journeyDate.toString())
                .activeTrains(trainRepository.countByActiveTrue())
                .totalBookings(bookingRepository.countByJourneyDateAndStatusIn(journeyDate, TRAVELLING_BOOKINGS))
                .confirmedBookings(bookingRepository.countByJourneyDateAndStatus(journeyDate, BookingStatus.CONFIRMED))
                .racBookings(bookingRepository.countByJourneyDateAndStatus(journeyDate, BookingStatus.RAC))
                .waitlistedBookings(bookingRepository.countByJourneyDateAndStatus(journeyDate, BookingStatus.WAITLISTED))
                .totalPassengers(boarding.values().stream().mapToLong(Long::longValue).sum())
                .boardedPassengers(boarding.getOrDefault(BoardingStatus.BOARDED, 0L))
                .notBoardedPassengers(boarding.getOrDefault(BoardingStatus.NOT_BOARDED, 0L))
                .noShowPassengers(boarding.getOrDefault(BoardingStatus.NO_SHOW, 0L))
                .openIncidents(incidentRepository.countByJourneyDateAndStatusIn(journeyDate, OPEN_INCIDENTS))
                .resolvedIncidents(incidentRepository.countByJourneyDateAndStatusIn(
                        journeyDate, List.of(IncidentStatus.RESOLVED)))
                .openAssistanceRequests(assistanceRequestRepository.countByBookingJourneyDateAndStatusIn(
                        journeyDate, OPEN_ASSISTANCE))
                .resolvedAssistanceRequests(assistanceRequestRepository.countByBookingJourneyDateAndStatusIn(
                        journeyDate, List.of(AssistanceStatus.RESOLVED)))
                .build();
    }
}
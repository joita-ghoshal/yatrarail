package com.yatrarail.service;

import com.yatrarail.dto.staff.BoardingRequest;
import com.yatrarail.dto.staff.IncidentRequest;
import com.yatrarail.dto.staff.IncidentUpdateRequest;
import com.yatrarail.dto.staff.IncidentDto;
import com.yatrarail.entity.BoardingStatus;
import com.yatrarail.entity.BookingPassenger;
import com.yatrarail.entity.BookingStatus;
import com.yatrarail.entity.PassengerStatus;
import com.yatrarail.entity.IncidentSeverity;
import com.yatrarail.entity.IncidentStatus;
import com.yatrarail.entity.IncidentType;
import com.yatrarail.entity.NotificationType;
import com.yatrarail.entity.TrainIncident;
import com.yatrarail.entity.User;
import com.yatrarail.exception.BadRequestException;
import com.yatrarail.exception.ConflictException;
import com.yatrarail.exception.ResourceNotFoundException;
import com.yatrarail.repository.BookingPassengerRepository;
import com.yatrarail.repository.TrainIncidentRepository;
import com.yatrarail.repository.TrainRepository;
import com.yatrarail.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

/**
 * Gate check-in and incident reporting for the staff portal.
 * <p>
 * The single most important rule in this class: marking a passenger {@link BoardingStatus#NO_SHOW}
 * records an operational fact and nothing else. It does not refund, does not cancel, and does not
 * touch the booking's financial state. A conductor working a busy platform will mis-scan
 * somebody eventually, and a mis-scan must never be able to move money. Refunds remain an explicit,
 * audited action through the cancellation flow.
 */
@Service
@RequiredArgsConstructor
public class BoardingService {

    private final BookingPassengerRepository bookingPassengerRepository;
    private final TrainIncidentRepository incidentRepository;
    private final TrainRepository trainRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;

    // ------------------------------------------------------------------
    // Gate check-in
    // ------------------------------------------------------------------

    /**
     * Records a passenger's boarding state.
     * <p>
     * Idempotent by design: marking the same state twice is a no-op rather than an error, because a
     * double-tap on a phone at a platform is routine and should not raise an alert. Moving to a
     * <em>different</em> state is allowed so a mis-scan can be corrected, and the marking staff
     * member and timestamp are always refreshed to show who last had the say.
     */
    @Transactional
    public BoardingResult markBoardingWithDetail(Long passengerId, BoardingRequest request, Long staffUserId) {
        if (request == null || request.getStatus() == null || request.getStatus().isBlank()) {
            throw new BadRequestException("Boarding status is required");
        }
        BoardingStatus target = parseBoardingStatus(request.getStatus());

        BookingPassenger passenger = bookingPassengerRepository.findById(passengerId)
                .orElseThrow(() -> new ResourceNotFoundException("Passenger not found"));
        User staffUser = userRepository.findById(staffUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Staff user not found"));

        assertTravelling(passenger);

        // Already in this state: a double-tap on a phone at a platform is routine, not an error.
        if (passenger.getBoardingStatus() != target) {
            passenger.setBoardingStatus(target);
            passenger.setBoardingMarkedBy(staffUser);
            passenger.setBoardingMarkedAt(LocalDateTime.now());
            bookingPassengerRepository.save(passenger);
        }
        return new BoardingResult(
                passenger.getId(),
                target,
                passenger.getFullName(),
                passenger.getBooking().getBookingReference(),
                passenger.getBoardingMarkedBy() != null ? passenger.getBoardingMarkedBy().getFullName() : null,
                passenger.getBoardingMarkedAt());
    }

    /**
     * Non-vacuity check for the service layer: a caller that ignores the returned result would
     * otherwise never notice if the write silently stopped happening.
     */
    @Transactional(readOnly = true)
    public BoardingStatus currentBoardingStatus(Long passengerId) {
        return bookingPassengerRepository.findById(passengerId)
                .orElseThrow(() -> new ResourceNotFoundException("Passenger not found"))
                .getBoardingStatus();
    }

    /**
     * Gate check-in is only meaningful for a passenger who is actually travelling. A cancelled or
     * expired passenger's seat has gone back to inventory, so a stale staff tab must not be able to
     * check them in and leave the dashboard counting a phantom on the train.
     */
    private void assertTravelling(BookingPassenger passenger) {
        PassengerStatus passengerStatus = passenger.getPassengerStatus();
        if (passengerStatus == PassengerStatus.CANCELLED || passengerStatus == PassengerStatus.EXPIRED) {
            throw new BadRequestException("Passenger " + passenger.getFullName()
                    + " is not travelling on this journey and cannot be checked in");
        }
        BookingStatus bookingStatus = passenger.getBooking().getStatus();
        if (bookingStatus == BookingStatus.CANCELLED
                || bookingStatus == BookingStatus.EXPIRED
                || bookingStatus == BookingStatus.PAYMENT_PENDING
                || bookingStatus == BookingStatus.PAYMENT_FAILED) {
            throw new BadRequestException("Booking " + passenger.getBooking().getBookingReference()
                    + " is not travelling on this journey and cannot be checked in");
        }
    }

    /**
     * What the gate gets back after a scan.
     * <p>
     * Includes the passenger name and reference deliberately: a conductor scanning a phone full of
     * unfamiliar codes needs to see <em>who</em> just boarded next to the new state, and returning
     * only the enum would force a manifest refetch for every single scan.
     */
    public record BoardingResult(
            Long passengerId,
            BoardingStatus status,
            String passengerName,
            String bookingReference,
            String markedByName,
            LocalDateTime markedAt) {
    }

    // ------------------------------------------------------------------
    // Incidents
    // ------------------------------------------------------------------

    /**
     * Files a delay or disruption against one train on one journey date, and tells the other staff
     * about it: an incident nobody heard about is not a report.
     */
    @Transactional
    public IncidentDto reportIncident(IncidentRequest request, Long staffUserId) {
        var train = trainRepository.findById(request.getTrainId())
                .orElseThrow(() -> new ResourceNotFoundException("Train not found"));
        User reporter = userRepository.findById(staffUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Staff user not found"));

        LocalDate journeyDate = parseDate(request.getJourneyDate());

        TrainIncident incident = TrainIncident.builder()
                .train(train)
                .journeyDate(journeyDate)
                .type(parseIncidentType(request.getType()))
                .severity(parseSeverity(request.getSeverity()))
                .status(IncidentStatus.OPEN)
                .delayMinutes(request.getDelayMinutes())
                .description(request.getDescription().trim())
                .reportedBy(reporter)
                .build();

        rejectDuplicateOpenIncident(incident);

        TrainIncident saved = incidentRepository.save(incident);
        notificationService.notifyStaff(NotificationType.STAFF_INCIDENT,
                saved.getType().name().replace('_', ' ') + " reported on " + train.getName(),
                saved.getDescription());
        return IncidentDto.from(saved);
    }

    /**
     * Moves an incident along its lifecycle. Reopening a resolved incident clears the resolution
     * fields rather than leaving a stale "resolved by" against an open report.
     */
    @Transactional
    public IncidentDto updateIncidentStatus(Long incidentId, IncidentUpdateRequest request, Long staffUserId) {
        IncidentStatus target = parseIncidentStatus(request.getStatus());
        TrainIncident incident = incidentRepository.findById(incidentId)
                .orElseThrow(() -> new ResourceNotFoundException("Incident not found"));
        User staffUser = userRepository.findById(staffUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Staff user not found"));

        incident.setStatus(target);
        if (target == IncidentStatus.RESOLVED) {
            incident.setResolvedBy(staffUser);
            incident.setResolvedAt(LocalDateTime.now());
        } else {
            incident.setResolvedBy(null);
            incident.setResolvedAt(null);
        }
        return IncidentDto.from(incidentRepository.save(incident));
    }

    @Transactional(readOnly = true)
    public List<IncidentDto> listIncidents(LocalDate journeyDate, String statusFilter) {
        if (statusFilter != null && !statusFilter.isBlank() && !statusFilter.equalsIgnoreCase("ALL")) {
            IncidentStatus status = parseIncidentStatus(statusFilter);
            return incidentRepository.findByStatusInOrderByReportedAtDesc(List.of(status)).stream()
                    .map(IncidentDto::from)
                    .toList();
        }
        return incidentRepository.findByJourneyDateOrderByReportedAtDesc(journeyDate).stream()
                .map(IncidentDto::from)
                .toList();
    }

    /**
     * Refuses a second, identical incident for the same train and day while the first is still open.
     * A duplicate open report is almost always the same news filed twice, and two open "CRITICAL
     * delay" entries send two staff to two phones. A <em>resolved</em> duplicate is allowed,
     * because the same service genuinely can break down twice.
     */
    private void rejectDuplicateOpenIncident(TrainIncident candidate) {
        boolean duplicate = incidentRepository.findByTrainIdAndJourneyDateOrderByReportedAtDesc(
                        candidate.getTrain().getId(), candidate.getJourneyDate())
                .stream()
                .anyMatch(existing -> existing.getType() == candidate.getType()
                        && existing.getStatus() != IncidentStatus.RESOLVED);
        if (duplicate) {
            throw new ConflictException("An open " + candidate.getType().name().toLowerCase(Locale.ROOT)
                    + " incident is already recorded for this train and date");
        }
    }

    // ------------------------------------------------------------------
    // Parsing helpers -- every enum arrives as a string from the client, so
    // a bad value must be a clear 400 rather than a NullPointerException or a 500.
    // ------------------------------------------------------------------

    private BoardingStatus parseBoardingStatus(String raw) {
        try {
            return BoardingStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Unknown boarding status: " + raw);
        }
    }

    private IncidentType parseIncidentType(String raw) {
        try {
            return IncidentType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Unknown incident type: " + raw);
        }
    }

    private IncidentSeverity parseSeverity(String raw) {
        try {
            return IncidentSeverity.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Unknown incident severity: " + raw);
        }
    }

    private IncidentStatus parseIncidentStatus(String raw) {
        try {
            return IncidentStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Unknown incident status: " + raw);
        }
    }

    private LocalDate parseDate(String raw) {
        try {
            return LocalDate.parse(raw.trim());
        } catch (Exception e) {
            throw new BadRequestException("Journey date must be in yyyy-MM-dd format");
        }
    }
}
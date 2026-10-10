package com.yatrarail.service;

import com.yatrarail.dto.staff.AssistanceDto;
import com.yatrarail.dto.staff.AssistanceRequestBody;
import com.yatrarail.dto.staff.AssistanceUpdateRequest;
import com.yatrarail.entity.AssistanceCategory;
import com.yatrarail.entity.AssistanceRequest;
import com.yatrarail.entity.AssistanceStatus;
import com.yatrarail.entity.BookingPassenger;
import com.yatrarail.entity.BookingStatus;
import com.yatrarail.entity.NotificationType;
import com.yatrarail.entity.PassengerStatus;
import com.yatrarail.entity.User;
import com.yatrarail.exception.BadRequestException;
import com.yatrarail.exception.ConflictException;
import com.yatrarail.exception.ResourceNotFoundException;
import com.yatrarail.repository.AssistanceRequestRepository;
import com.yatrarail.repository.BookingPassengerRepository;
import com.yatrarail.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

/**
 * Assistance requests: a passenger asks for help, staff pick it up and close it.
 * <p>
 * A request is pinned to one passenger of one booking, so three people travelling together can
 * ask for a wheelchair for exactly one of them and staff can see which seat to walk to.
 */
@Service
@RequiredArgsConstructor
public class AssistanceService {

    private final AssistanceRequestRepository assistanceRequestRepository;
    private final BookingPassengerRepository bookingPassengerRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;

    /** A passenger asks for help on one of their own passengers. */
    @Transactional
    public AssistanceDto raise(Long bookingId, AssistanceRequestBody body, Long passengerUserId) {
        if (body.getPassengerId() == null) {
            throw new BadRequestException("Passenger is required");
        }
        AssistanceCategory category = parseCategory(body.getCategory());

        BookingPassenger passenger = bookingPassengerRepository.findById(body.getPassengerId())
                .orElseThrow(() -> new ResourceNotFoundException("Passenger not found"));

        // Ownership is checked before anything else so a stranger cannot use this endpoint to
        // discover that somebody else's passenger id exists.
        if (!passenger.getBooking().getId().equals(bookingId)) {
            throw new ResourceNotFoundException("Passenger not found on this booking");
        }
        if (!passenger.getBooking().getUser().getId().equals(passengerUserId)) {
            throw new ResourceNotFoundException("Booking not found");
        }
        if (passenger.getBooking().getStatus() == BookingStatus.CANCELLED
                || passenger.getPassengerStatus() == PassengerStatus.CANCELLED) {
            throw new BadRequestException("This passenger's journey is cancelled, so there is nothing to assist with");
        }

        boolean alreadyOpen = assistanceRequestRepository
                .existsByBookingPassengerIdAndCategoryAndStatusIn(
                        passenger.getId(), category, List.of(AssistanceStatus.OPEN, AssistanceStatus.ACKNOWLEDGED));
        if (alreadyOpen) {
            throw new ConflictException("An open request for this help already exists for this passenger");
        }

        AssistanceRequest request = AssistanceRequest.builder()
                .bookingPassenger(passenger)
                .booking(passenger.getBooking())
                .category(category)
                .status(AssistanceStatus.OPEN)
                .details(body.getDetails() == null || body.getDetails().isBlank()
                        ? null
                        : body.getDetails().trim())
                .build();

        AssistanceRequest saved = assistanceRequestRepository.save(request);

        // The passenger asked; staff need to know it arrived.
        notificationService.notifyStaff(NotificationType.ASSISTANCE_REQUEST,
                "Assistance request: " + saved.getCategory().name().replace('_', ' ').toLowerCase(Locale.ROOT),
                passenger.getFullName() + " on booking "
                        + saved.getBooking().getBookingReference() + " needs assistance.");
        return AssistanceDto.from(saved);
    }

    /**
     * Moves a request along its lifecycle and records who picked it up. Reopening clears the
     * resolution fields so an open request never carries a stale "resolved by".
     */
    @Transactional
    public AssistanceDto update(Long requestId, AssistanceUpdateRequest body, Long staffUserId) {
        AssistanceStatus target = parseStatus(body.getStatus());
        AssistanceRequest request = assistanceRequestRepository.findById(requestId)
                .orElseThrow(() -> new ResourceNotFoundException("Assistance request not found"));
        User staffUser = userRepository.findById(staffUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Staff user not found"));

        request.setStatus(target);
        if (target == AssistanceStatus.OPEN) {
            request.setAssignedTo(null);
            request.setResolvedAt(null);
        } else {
            request.setAssignedTo(staffUser);
            request.setResolvedAt(target == AssistanceStatus.RESOLVED ? LocalDateTime.now() : null);
        }
        return AssistanceDto.from(assistanceRequestRepository.save(request));
    }

    @Transactional(readOnly = true)
    public List<AssistanceDto> listForBooking(Long bookingId) {
        return assistanceRequestRepository.findByBookingIdOrderByRaisedAtAsc(bookingId).stream()
                .map(AssistanceDto::from)
                .toList();
    }

    /**
     * The staff queue: a journey date, or every status when the caller is triaging across days.
     */
    @Transactional(readOnly = true)
    public List<AssistanceDto> list(LocalDate journeyDate, String statusFilter) {
        if (statusFilter != null && !statusFilter.isBlank() && !statusFilter.equalsIgnoreCase("ALL")) {
            AssistanceStatus status = parseStatus(statusFilter);
            return assistanceRequestRepository.findByStatusInOrderByRaisedAtAsc(List.of(status)).stream()
                    .map(AssistanceDto::from)
                    .toList();
        }
        return assistanceRequestRepository.findByBookingJourneyDateOrderByRaisedAtAsc(journeyDate).stream()
                .map(AssistanceDto::from)
                .toList();
    }

    private AssistanceCategory parseCategory(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BadRequestException("Category is required");
        }
        try {
            return AssistanceCategory.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Unknown assistance category: " + raw);
        }
    }

    private AssistanceStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BadRequestException("Status is required");
        }
        try {
            return AssistanceStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Unknown assistance status: " + raw);
        }
    }
}
package com.yatrarail.service;

import com.yatrarail.entity.RouteStation;
import com.yatrarail.entity.Train;
import com.yatrarail.exception.BadRequestException;
import com.yatrarail.repository.RouteStationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The single place that answers "can this train actually run this journey?".
 * <p>
 * Before Phase 2 the booking endpoint trusted whatever the client sent: any two stations were
 * accepted, they did not have to be on the train's route, they did not have to be in forward
 * order, and the operating days were matched with a naive substring test. Every one of those is a
 * validation rule enforced here so that booking, quoting and search all agree.
 */
@Service
@RequiredArgsConstructor
public class TrainScheduleValidator {

    private final RouteStationRepository routeStationRepository;

    /** Upper bound on how far ahead a booking may be made, in days. */
    public static final int MAX_ADVANCE_DAYS = 120;

    /**
     * Exact-match test for the {@code runsOn} column ("MON,TUE,WED"). The previous implementation
     * used {@code String.contains}, which would accept a train that runs on "SUNDAY" when asked for
     * a "SUN" service and generally matched on substrings instead of day tokens.
     */
    public boolean runsOnDay(Train train, LocalDate date) {
        return dayCodes(train).contains(dayCode(date.getDayOfWeek()));
    }

    public List<String> dayCodes(Train train) {
        if (train.getRunsOn() == null || train.getRunsOn().isBlank()) {
            return List.of();
        }
        return Arrays.stream(train.getRunsOn().split(","))
                .map(code -> code.trim().toUpperCase(Locale.ROOT))
                .filter(code -> !code.isEmpty())
                .collect(Collectors.toList());
    }

    public Set<String> dayCodeSet(Train train) {
        return Set.copyOf(dayCodes(train));
    }

    public static String dayCode(DayOfWeek day) {
        String name = day.name(); // MONDAY
        return name.substring(0, 3);
    }

    /**
     * Resolves the two route stations for a journey and verifies that the train stops at both and
     * that the boarding point comes strictly before the alighting point.
     *
     * @return the segment distance in km (always &gt; 0 for a valid segment)
     */
    @Transactional(readOnly = true)
    public Segment resolveSegment(Train train, Long sourceStationId, Long destinationStationId) {
        RouteStation source = routeStationRepository.findByTrainIdAndStationId(train.getId(), sourceStationId)
                .orElseThrow(() -> new BadRequestException(
                        sourceStationId.equals(destinationStationId)
                                ? "Source and destination stations cannot be the same"
                                : "The selected train does not stop at the chosen source station"));
        RouteStation destination = routeStationRepository.findByTrainIdAndStationId(train.getId(), destinationStationId)
                .orElseThrow(() -> new BadRequestException(
                        "The selected train does not stop at the chosen destination station"));

        if (source.getSequenceNumber().equals(destination.getSequenceNumber())) {
            throw new BadRequestException("Source and destination stations cannot be the same");
        }
        if (source.getSequenceNumber() > destination.getSequenceNumber()) {
            throw new BadRequestException("This train runs from "
                    + destination.getStation().getName() + " to " + source.getStation().getName()
                    + ". Please choose the stations in the correct travel order.");
        }

        int distance = destination.getDistanceFromSourceKm() - source.getDistanceFromSourceKm();
        if (distance <= 0) {
            throw new BadRequestException("The route distance for this segment is not configured correctly");
        }
        return new Segment(source, destination, distance);
    }

    @Transactional(readOnly = true)
    public int totalDistanceKm(Long trainId) {
        return routeStationRepository.findTotalDistanceKm(trainId) == null
                ? 0
                : routeStationRepository.findTotalDistanceKm(trainId);
    }

    /** Validates that a journey date is bookable at all. */
    public void validateJourneyDate(LocalDate journeyDate) {
        if (journeyDate == null) {
            throw new BadRequestException("Journey date is required");
        }
        LocalDate today = LocalDate.now();
        if (journeyDate.isBefore(today)) {
            throw new BadRequestException("Journey date cannot be in the past");
        }
        if (journeyDate.isAfter(today.plusDays(MAX_ADVANCE_DAYS))) {
            throw new BadRequestException("Bookings open only "
                    + MAX_ADVANCE_DAYS + " days in advance");
        }
    }

    /** Validates that the train is in service on the requested day. */
    public void validateServiceDay(Train train, LocalDate journeyDate) {
        if (!Boolean.TRUE.equals(train.getActive())) {
            throw new BadRequestException("This train is not currently in service");
        }
        if (!runsOnDay(train, journeyDate)) {
            throw new BadRequestException(train.getName() + " (" + train.getTrainNumber()
                    + ") does not run on " + journeyDate.getDayOfWeek().name().charAt(0)
                    + journeyDate.getDayOfWeek().name().substring(1).toLowerCase(Locale.ROOT)
                    + ". It runs on: " + train.getRunsOn());
        }
    }

    /** A validated boarding/alighting pair plus the distance of that segment. */
    public record Segment(RouteStation source, RouteStation destination, int distanceKm) {
    }
}

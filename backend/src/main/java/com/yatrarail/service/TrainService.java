package com.yatrarail.service;

import com.yatrarail.dto.admin.CoachDto;
import com.yatrarail.dto.admin.CoachRequest;
import com.yatrarail.dto.admin.RouteStationRequest;
import com.yatrarail.dto.admin.TrainDto;
import com.yatrarail.dto.admin.TrainRequest;
import com.yatrarail.config.CacheConfig.CacheNames;
import com.yatrarail.dto.train.*;
import com.yatrarail.entity.*;
import com.yatrarail.exception.BadRequestException;
import com.yatrarail.exception.ConflictException;
import com.yatrarail.exception.ResourceNotFoundException;
import com.yatrarail.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TrainService {

    private final TrainRepository trainRepository;
    private final StationRepository stationRepository;
    private final RouteStationRepository routeStationRepository;
    private final CoachRepository coachRepository;
    private final SeatRepository seatRepository;
    private final BookingRepository bookingRepository;
    private final FareCalculationService fareCalculationService;
    private final SeatAllocationService seatAllocationService;
    private final TrainScheduleValidator scheduleValidator;

    /**
     * Phase 7: every admin mutation below takes the acting admin's id so the change is attributable.
     * The audit row is written by the service, not the controller, because only here is the "after"
     * state known.
     */
    private final UserRepository userRepository;
    private final AuditService auditService;

    /** "Priya Sharma (ADMIN)" — the prefix every audit summary starts with. */
    private String actor(Long actorId) {
        if (actorId == null) {
            return "System";
        }
        return userRepository.findById(actorId)
                .map(u -> u.getFullName() + " (" + u.getRole().name() + ")")
                .orElse("System");
    }

    // Backward-compatible overloads for tests and internal callers that have no acting admin.
    @Transactional
    public TrainDto createTrain(TrainRequest request) {
        return createTrain(request, null);
    }

    @Transactional
    public TrainDto updateTrain(Long trainId, TrainRequest request) {
        return updateTrain(trainId, request, null);
    }

    @Transactional
    public TrainDto setTrainActive(Long trainId, boolean active) {
        return setTrainActive(trainId, active, null);
    }

    @CacheEvict(cacheNames = CacheNames.TRAIN_ROUTE, key = "#trainId")
    @Transactional
    public void deleteTrain(Long trainId) {
        deleteTrain(trainId, null);
    }

    @Transactional
    public CoachDto addCoach(Long trainId, CoachRequest request) {
        return addCoach(trainId, request, null);
    }

    @Transactional
    public CoachDto setCoachActive(Long coachId, boolean active) {
        return setCoachActive(coachId, active, null);
    }

    @Transactional
    public CoachDto updateCoach(Long coachId, CoachRequest request) {
        return updateCoach(coachId, request, null);
    }

    @CacheEvict(cacheNames = CacheNames.TRAIN_ROUTE, key = "#trainId")
    @Transactional
    public List<RouteStationDto> replaceRoute(Long trainId, List<RouteStationRequest> route) {
        return replaceRoute(trainId, route, null);
    }

    // ---------- Search ----------

    @Transactional(readOnly = true)
    public List<TrainSearchResult> search(TrainSearchRequest request) {
        if (request.getSourceCode() == null || request.getDestinationCode() == null) {
            throw new BadRequestException("Source and destination stations are required");
        }
        if (request.getSourceCode().equalsIgnoreCase(request.getDestinationCode())) {
            throw new BadRequestException("Source and destination stations cannot be the same");
        }
        if (request.getJourneyDate() == null) {
            throw new BadRequestException("Journey date is required");
        }
        scheduleValidator.validateJourneyDate(request.getJourneyDate());

        Station source = stationRepository.findByCode(request.getSourceCode().trim().toUpperCase(Locale.ROOT))
                .orElseThrow(() -> new ResourceNotFoundException("Source station not found"));
        Station destination = stationRepository.findByCode(request.getDestinationCode().trim().toUpperCase(Locale.ROOT))
                .orElseThrow(() -> new ResourceNotFoundException("Destination station not found"));

        // Any train that serves the leg in this direction, not just origin-to-terminus services:
        // a passenger booking New Delhi -> Lucknow on a train that continues to Howrah must see it.
        List<Train> trains = trainRepository.findTrainsServingSegment(source.getId(), destination.getId());

        SeatClass filter = parseSeatClassFilter(request.getSeatClass());

        return trains.stream()
                // Exact day-token match instead of the old String.contains() test.
                .filter(t -> scheduleValidator.runsOnDay(t, request.getJourneyDate()))
                .flatMap(t -> toSearchResult(t, source, destination, request.getJourneyDate()).stream())
                .filter(r -> filter == null || r.getClassAvailability().stream()
                        .anyMatch(c -> filter.name().equals(c.getSeatClass())))
                .collect(Collectors.toList());
    }

    private SeatClass parseSeatClassFilter(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return SeatClass.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Invalid seat class filter: " + raw);
        }
    }

    /** Empty when the train cannot serve this segment (e.g. it does not stop at one of the ends). */
    private Optional<TrainSearchResult> toSearchResult(Train t, Station source, Station destination,
                                                      LocalDate journeyDate) {
        TrainScheduleValidator.Segment segment;
        try {
            segment = scheduleValidator.resolveSegment(t, source.getId(), destination.getId());
        } catch (BadRequestException ex) {
            return Optional.empty();
        }
        List<ClassAvailability> availability = buildAvailability(t, journeyDate, segment.distanceKm());
        return Optional.of(TrainSearchResult.builder()
                .trainId(t.getId())
                .trainNumber(t.getTrainNumber())
                .trainName(t.getName())
                .trainType(t.getType().name())
                .sourceStationName(source.getName())
                .sourceStationCode(source.getCode())
                .sourceStationId(source.getId())
                .destinationStationName(destination.getName())
                .destinationStationCode(destination.getCode())
                .destinationStationId(destination.getId())
                .distanceKm(segment.distanceKm())
                .departureTime(t.getDepartureTime())
                .arrivalTime(t.getArrivalTime())
                .durationMinutes(t.getDurationMinutes())
                .journeyDate(journeyDate)
                .classAvailability(availability)
                .build());
    }

    /**
     * Per-class availability for one journey date, priced for the requested segment.
     * <p>
     * The old implementation ran one count query per seat; availability now comes from a single
     * {@code seat_reservations} lookup per coach, and the fare uses the segment distance rather than
     * the train's full route length.
     */
    private List<ClassAvailability> buildAvailability(Train train, LocalDate journeyDate, int distanceKm) {
        List<Coach> coaches = coachRepository.findByTrainIdAndActiveTrue(train.getId());
        List<ClassAvailability> result = new ArrayList<>();
        for (Coach coach : coaches) {
            SeatAllocationService.ClassAvailabilitySnapshot snapshot =
                    seatAllocationService.availability(coach, journeyDate);
            result.add(ClassAvailability.builder()
                    .coachId(coach.getId())
                    .coachCode(coach.getCoachCode())
                    .seatClass(coach.getSeatClass().name())
                    .seatClassLabel(coach.getSeatClass().getLabel())
                    .fare(fareCalculationService.calculateFarePerPassenger(coach, distanceKm))
                    .distanceKm(distanceKm)
                    .totalSeats(snapshot.totalSeats())
                    .availableSeats(snapshot.availableSeats())
                    .racSeats(snapshot.racSeatsAvailable())
                    .status(snapshot.status())
                    .build());
        }
        return result;
    }

    // ---------- Details ----------

    @Transactional(readOnly = true)
    public TrainDetailsDto getDetails(Long trainId, LocalDate journeyDate,
                                      Long sourceStationId, Long destinationStationId) {
        Train train = trainRepository.findById(trainId)
                .orElseThrow(() -> new ResourceNotFoundException("Train not found"));
        List<RouteStation> route = routeStationRepository.findByTrainIdOrderBySequenceNumberAsc(trainId);
        List<RouteStationDto> routeDtos = route.stream().map(rs -> RouteStationDto.builder()
                .sequenceNumber(rs.getSequenceNumber())
                .stationId(rs.getStation().getId())
                .stationCode(rs.getStation().getCode())
                .stationName(rs.getStation().getName())
                .arrivalTime(rs.getArrivalTime())
                .departureTime(rs.getDepartureTime())
                .haltMinutes(rs.getHaltMinutes())
                .distanceFromSourceKm(rs.getDistanceFromSourceKm())
                .build()).toList();

        LocalDate effectiveDate = journeyDate != null ? journeyDate : LocalDate.now();

        // Price the classes for the segment the passenger is actually looking at, defaulting to the
        // train's full route when no segment is given.
        Integer segmentDistance = null;
        if (sourceStationId != null && destinationStationId != null) {
            try {
                segmentDistance = scheduleValidator.resolveSegment(train, sourceStationId, destinationStationId)
                        .distanceKm();
            } catch (BadRequestException ex) {
                throw ex;
            }
        }
        int fareDistance = segmentDistance != null
                ? segmentDistance
                : scheduleValidator.totalDistanceKm(trainId);

        List<ClassAvailability> classes = buildAvailability(train, effectiveDate, fareDistance);

        return TrainDetailsDto.builder()
                .trainId(train.getId())
                .trainNumber(train.getTrainNumber())
                .trainName(train.getName())
                .trainType(train.getType().name())
                .departureTime(train.getDepartureTime())
                .arrivalTime(train.getArrivalTime())
                .durationMinutes(train.getDurationMinutes())
                .runsOn(train.getRunsOn())
                .runsOnDays(scheduleValidator.dayCodes(train))
                .distanceKm(fareDistance)
                .route(routeDtos)
                .classes(classes)
                .build();
    }

    // ---------- Admin CRUD ----------

    @Transactional(readOnly = true)
    public List<TrainDto> getAllTrains() {
        return trainRepository.findAll().stream().map(TrainDto::from).toList();
    }

    @Transactional
    public TrainDto createTrain(TrainRequest request, Long actorId) {
        if (trainRepository.existsByTrainNumber(request.getTrainNumber())) {
            throw new ConflictException("A train with this number already exists");
        }
        Station source = stationRepository.findById(request.getSourceStationId())
                .orElseThrow(() -> new ResourceNotFoundException("Source station not found"));
        Station destination = stationRepository.findById(request.getDestinationStationId())
                .orElseThrow(() -> new ResourceNotFoundException("Destination station not found"));
        if (source.getId().equals(destination.getId())) {
            throw new BadRequestException("Source and destination stations cannot be the same");
        }
        TrainType type;
        try {
            type = TrainType.valueOf(request.getType().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Invalid train type: " + request.getType());
        }

        Train train = Train.builder()
                .trainNumber(request.getTrainNumber())
                .name(request.getName())
                .type(type)
                .sourceStation(source)
                .destinationStation(destination)
                .departureTime(request.getDepartureTime())
                .arrivalTime(request.getArrivalTime())
                .durationMinutes(request.getDurationMinutes())
                .runsOn(request.getRunsOn())
                .active(true)
                .build();
        train = trainRepository.save(train);

        if (request.getRoute() != null) {
            for (RouteStationRequest rs : request.getRoute()) {
                Station station = stationRepository.findById(rs.getStationId())
                        .orElseThrow(() -> new ResourceNotFoundException("Route station not found"));
                RouteStation routeStation = RouteStation.builder()
                        .train(train)
                        .station(station)
                        .sequenceNumber(rs.getSequenceNumber())
                        .arrivalTime(rs.getArrivalTime())
                        .departureTime(rs.getDepartureTime())
                        .haltMinutes(rs.getHaltMinutes() != null ? rs.getHaltMinutes() : 0)
                        .distanceFromSourceKm(rs.getDistanceFromSourceKm())
                        .build();
                routeStationRepository.save(routeStation);
            }
        }
        auditService.record(AuditService.TRAIN_CREATED, "TRAIN", train.getId(),
                actor(actorId) + " created train " + train.getTrainNumber() + " ("
                        + train.getName() + ", " + source.getCode() + " to " + destination.getCode() + ")",
                actorId);
        return TrainDto.from(train);
    }

    @Transactional
    public TrainDto updateTrain(Long trainId, TrainRequest request, Long actorId) {
        Train train = trainRepository.findById(trainId)
                .orElseThrow(() -> new ResourceNotFoundException("Train not found"));
        Station source = stationRepository.findById(request.getSourceStationId())
                .orElseThrow(() -> new ResourceNotFoundException("Source station not found"));
        Station destination = stationRepository.findById(request.getDestinationStationId())
                .orElseThrow(() -> new ResourceNotFoundException("Destination station not found"));
        TrainType type;
        try {
            type = TrainType.valueOf(request.getType().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Invalid train type: " + request.getType());
        }
        train.setName(request.getName());
        train.setType(type);
        train.setSourceStation(source);
        train.setDestinationStation(destination);
        train.setDepartureTime(request.getDepartureTime());
        train.setArrivalTime(request.getArrivalTime());
        train.setDurationMinutes(request.getDurationMinutes());
        train.setRunsOn(request.getRunsOn());
        Train saved = trainRepository.save(train);
        auditService.record(AuditService.TRAIN_UPDATED, "TRAIN", saved.getId(),
                actor(actorId) + " updated train " + saved.getTrainNumber()
                        + " (" + saved.getName() + ", " + source.getCode() + " to " + destination.getCode() + ")",
                actorId);
        return TrainDto.from(saved);
    }

    @Transactional
    public TrainDto setTrainActive(Long trainId, boolean active, Long actorId) {
        Train train = trainRepository.findById(trainId)
                .orElseThrow(() -> new ResourceNotFoundException("Train not found"));
        if (active == Boolean.TRUE.equals(train.getActive())) {
            return TrainDto.from(train);
        }
        train.setActive(active);
        Train saved = trainRepository.save(train);
        auditService.record(active ? AuditService.TRAIN_ACTIVATED : AuditService.TRAIN_DEACTIVATED,
                "TRAIN", saved.getId(),
                actor(actorId) + (active ? " re-activated " : " deactivated ")
                        + "train " + saved.getTrainNumber(), actorId);
        return TrainDto.from(saved);
    }

    @CacheEvict(cacheNames = CacheNames.TRAIN_ROUTE, key = "#trainId")
    @Transactional
    public void deleteTrain(Long trainId, Long actorId) {
        Train train = trainRepository.findById(trainId)
                .orElseThrow(() -> new ResourceNotFoundException("Train not found"));
        if (bookingRepository.countByTrainId(trainId) > 0) {
            throw new ConflictException("Cannot delete a train with existing bookings. Deactivate it instead.");
        }
        String label = train.getTrainNumber() + " (" + train.getName() + ")";
        for (Coach coach : coachRepository.findByTrainId(trainId)) {
            seatRepository.deleteByCoachId(coach.getId());
        }
        coachRepository.deleteByTrainId(trainId);
        routeStationRepository.deleteByTrainId(trainId);
        trainRepository.delete(train);
        auditService.record(AuditService.TRAIN_DELETED, "TRAIN", trainId,
                actor(actorId) + " deleted train " + label, actorId);
    }

    @Transactional
    public CoachDto addCoach(Long trainId, CoachRequest request, Long actorId) {
        Train train = trainRepository.findById(trainId)
                .orElseThrow(() -> new ResourceNotFoundException("Train not found"));
        SeatClass seatClass;
        try {
            seatClass = SeatClass.valueOf(request.getSeatClass().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Invalid seat class: " + request.getSeatClass());
        }
        Coach coach = Coach.builder()
                .train(train)
                .coachCode(request.getCoachCode())
                .seatClass(seatClass)
                .totalSeats(request.getTotalSeats())
                .baseFare(request.getBaseFare())
                .racCapacity(request.getRacCapacity() == null ? 0 : Math.max(0, request.getRacCapacity()))
                .active(true)
                .build();
        coach = coachRepository.save(coach);

        for (int i = 1; i <= request.getTotalSeats(); i++) {
            String berthType = deriveBerthType(seatClass, i);
            Seat seat = Seat.builder()
                    .coach(coach)
                    .seatNumber(i)
                    .berthType(berthType)
                    .active(true)
                    .build();
            seatRepository.save(seat);
        }
        auditService.record(AuditService.COACH_ADDED, "COACH", coach.getId(),
                actor(actorId) + " added coach " + coach.getCoachCode() + " ("
                        + seatClass.name() + ", " + coach.getTotalSeats() + " seats) to train "
                        + train.getTrainNumber(), actorId);
        return CoachDto.from(coach);
    }

    private String deriveBerthType(SeatClass seatClass, int seatNumber) {
        if (seatClass == SeatClass.CHAIR_CAR || seatClass == SeatClass.SECOND_SEATING) {
            return seatNumber % 2 == 0 ? "AISLE" : "WINDOW";
        }
        int pos = seatNumber % 8;
        return switch (pos) {
            case 1, 4 -> "LOWER";
            case 2, 5 -> "MIDDLE";
            case 3, 6 -> "UPPER";
            case 7 -> "SIDE_LOWER";
            default -> "SIDE_UPPER";
        };
    }

    @Transactional
    public CoachDto setCoachActive(Long coachId, boolean active, Long actorId) {
        Coach coach = coachRepository.findById(coachId)
                .orElseThrow(() -> new ResourceNotFoundException("Coach not found"));
        if (active == Boolean.TRUE.equals(coach.getActive())) {
            return CoachDto.from(coach);
        }
        coach.setActive(active);
        Coach saved = coachRepository.save(coach);
        auditService.record(active ? AuditService.COACH_ACTIVATED : AuditService.COACH_DEACTIVATED,
                "COACH", saved.getId(),
                actor(actorId) + (active ? " re-activated " : " deactivated ")
                        + "coach " + saved.getCoachCode(), actorId);
        return CoachDto.from(saved);
    }

    @Transactional(readOnly = true)
    public List<CoachDto> getCoachesForTrain(Long trainId) {
        return coachRepository.findByTrainId(trainId).stream().map(CoachDto::from).toList();
    }

    // ------------------------------------------------------------------
    // Phase 7: route editing and coach editing
    // ------------------------------------------------------------------

    /**
     * Replaces a train's route stops.
     * <p>
     * Until this existed, {@code TrainRequest.route} was accepted on create and then silently ignored
     * on update, which is the worst kind of bug: the form saved, the network call returned 200, and
     * the route was unchanged. A dedicated endpoint makes the intent explicit.
     * <p>
     * The whole list is replaced rather than merged. A partial update of an ordered stop list is
     * ambiguous — is sequence 3 being renumbered or removed? — and a config screen showing a list
     * always sends the whole list anyway.
     */
    @CacheEvict(cacheNames = CacheNames.TRAIN_ROUTE, key = "#trainId")
    @Transactional
    public List<RouteStationDto> replaceRoute(Long trainId, List<RouteStationRequest> route, Long actorId) {
        Train train = trainRepository.findById(trainId)
                .orElseThrow(() -> new ResourceNotFoundException("Train not found"));
        if (route == null || route.isEmpty()) {
            throw new BadRequestException("A route must contain at least one stop");
        }
        // Sequence numbers must be unique and ordered from 1, otherwise two stops share a position
        // and the "next stop" a passenger is shown becomes ambiguous.
        List<Integer> sequences = route.stream()
                .map(RouteStationRequest::getSequenceNumber)
                .sorted()
                .toList();
        for (int i = 0; i < sequences.size(); i++) {
            if (sequences.get(i) != i + 1) {
                throw new BadRequestException(
                        "Route stops must be numbered 1 to " + route.size() + " with no gaps or duplicates");
            }
        }
        // The same station twice on one route is always a mistake.
        long distinctStations = route.stream().map(RouteStationRequest::getStationId).distinct().count();
        if (distinctStations != route.size()) {
            throw new BadRequestException("A station cannot appear twice on the same route");
        }

        routeStationRepository.deleteByTrainId(trainId);
        List<RouteStation> saved = new ArrayList<>(route.size());
        for (RouteStationRequest rs : route) {
            Station station = stationRepository.findById(rs.getStationId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Route station " + rs.getStationId() + " not found"));
            if (!Boolean.TRUE.equals(station.getActive())) {
                throw new BadRequestException(
                        "Station " + station.getCode() + " is deactivated and cannot be on a route");
            }
            saved.add(routeStationRepository.save(RouteStation.builder()
                    .train(train)
                    .station(station)
                    .sequenceNumber(rs.getSequenceNumber())
                    .arrivalTime(rs.getArrivalTime())
                    .departureTime(rs.getDepartureTime())
                    .haltMinutes(rs.getHaltMinutes() != null ? rs.getHaltMinutes() : 0)
                    .distanceFromSourceKm(rs.getDistanceFromSourceKm())
                    .build()));
        }
        saved.sort(java.util.Comparator.comparingInt(RouteStation::getSequenceNumber));
        auditService.record(AuditService.TRAIN_ROUTE_UPDATED, "TRAIN", trainId,
                actor(actorId) + " replaced the route of train " + train.getTrainNumber()
                        + " with " + saved.size() + " stops", actorId);
        return saved.stream().map(RouteStationDto::from).toList();
    }

    /**
     * Phase 10 — a train's route is stable master data read on every search and detail page, and it
     * changes only when an administrator rewrites the schedule. It is cached per train and evicted
     * by id on {@link #replaceRoute} and on delete, so a schedule edit is visible immediately while
     * the read path stays off the database.
     */
    @Cacheable(cacheNames = CacheNames.TRAIN_ROUTE, key = "#trainId")
    @Transactional(readOnly = true)
    public List<RouteStationDto> getRoute(Long trainId) {
        if (!trainRepository.existsById(trainId)) {
            throw new ResourceNotFoundException("Train not found");
        }
        return routeStationRepository.findByTrainIdOrderBySequenceNumberAsc(trainId)
                .stream().map(RouteStationDto::from).toList();
    }

    /**
     * Edits a coach in place.
     * <p>
     * Seats are <b>not</b> regenerated. They are the physical inventory a passenger was allocated, and
     * a seat row that disappears underneath an existing reservation would strand a ticketed
     * passenger. So the two seat-affecting fields are guarded rather than applied:
     * <ul>
     *   <li>the seat class cannot change once the coach exists, because berth types (lower/middle/
     *       upper vs window/aisle) are derived from it at creation time;</li>
     *   <li>{@code totalSeats} cannot shrink below the highest seat number in use.</li>
     * </ul>
     * Growing the coach is allowed and simply appends new seats. An operator who needs to relayout
     * must create a new coach, which is the only change that cannot corrupt a held seat.
     */
    @Transactional
    public CoachDto updateCoach(Long coachId, CoachRequest request, Long actorId) {
        Coach coach = coachRepository.findById(coachId)
                .orElseThrow(() -> new ResourceNotFoundException("Coach not found"));
        SeatClass seatClass;
        try {
            seatClass = SeatClass.valueOf(request.getSeatClass().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Invalid seat class: " + request.getSeatClass());
        }
        if (seatClass != coach.getSeatClass()) {
            throw new BadRequestException(
                    "Seat class cannot be changed after the coach exists; the berth layout was built for "
                            + coach.getSeatClass() + ". Add a new coach instead.");
        }
        if (!request.getCoachCode().equalsIgnoreCase(coach.getCoachCode())
                && coachRepository.existsByTrainIdAndCoachCodeIgnoreCase(coach.getTrain().getId(),
                        request.getCoachCode())) {
            throw new ConflictException("That coach code is already used on this train");
        }

        int highestSeatInUse = seatRepository.findMaxSeatNumberByCoachId(coachId);
        int requestedSeats = request.getTotalSeats();
        if (requestedSeats < highestSeatInUse) {
            throw new ConflictException("This coach has seats allocated up to " + highestSeatInUse
                    + "; the seat count cannot be reduced below that");
        }

        coach.setCoachCode(request.getCoachCode());
        coach.setTotalSeats(requestedSeats);
        coach.setBaseFare(request.getBaseFare());
        coach.setRacCapacity(request.getRacCapacity() == null ? 0 : Math.max(0, request.getRacCapacity()));
        Coach saved = coachRepository.save(coach);

        // Growing the coach appends the missing seats; existing seat rows are never touched.
        int existingSeats = (int) seatRepository.countByCoachId(coachId);
        for (int i = existingSeats + 1; i <= requestedSeats; i++) {
            seatRepository.save(Seat.builder()
                    .coach(saved)
                    .seatNumber(i)
                    .berthType(deriveBerthType(seatClass, i))
                    .active(true)
                    .build());
        }
        auditService.record(AuditService.COACH_UPDATED, "COACH", saved.getId(),
                actor(actorId) + " updated coach " + saved.getCoachCode() + " to "
                        + saved.getTotalSeats() + " seats", actorId);
        return CoachDto.from(saved);
    }
}

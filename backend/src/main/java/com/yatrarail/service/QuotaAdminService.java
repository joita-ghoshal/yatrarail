package com.yatrarail.service;

import com.yatrarail.config.CacheConfig.CacheNames;
import com.yatrarail.dto.admin.FareRuleDto;
import com.yatrarail.dto.booking.PassengerDto;
import com.yatrarail.dto.fare.FareBreakdownDto;
import com.yatrarail.dto.fare.FarePreviewRequest;
import com.yatrarail.dto.quota.QuotaConfigDto;
import com.yatrarail.dto.quota.QuotaConfigRequest;
import com.yatrarail.dto.quota.QuotaFareDto;
import com.yatrarail.dto.quota.QuotaFareRequest;
import com.yatrarail.entity.*;
import com.yatrarail.exception.BadRequestException;
import com.yatrarail.exception.ConflictException;
import com.yatrarail.exception.ResourceNotFoundException;
import com.yatrarail.repository.*;
import com.yatrarail.service.FareCalculationService.PassengerFare;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Administrator-side management of the two things Phase 4 makes configurable.
 * <p>
 * The two are deliberately separate because they have different lifetimes:
 * <ul>
 *   <li>{@link QuotaConfig} — <i>how many</i> seats of a class a train keeps aside, per train and
 *       class. Tied to the physical inventory of one train.</li>
 *   <li>{@link QuotaFare} — <i>how much off</i> a quota earns, per class and quota. A property of the
 *       class, identical for every train.</li>
 * </ul>
 * Both edits are validated against reality rather than trusted: a split that adds up to more seats
 * than the train owns is rejected, and so is a reduction that would leave already-confirmed bookings
 * sitting outside a quota that no longer exists. That second guard is what stops an administrator
 * from retroactively invalidating a ticket somebody has already paid for.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuotaAdminService {

    private final QuotaConfigRepository quotaConfigRepository;
    private final QuotaFareRepository quotaFareRepository;
    private final CoachRepository coachRepository;
    private final TrainRepository trainRepository;
    private final SeatReservationRepository seatReservationRepository;
    private final FareRuleRepository fareRuleRepository;
    private final FareCalculationService fareCalculationService;
    private final QuotaService quotaService;
    private final TrainScheduleValidator trainScheduleValidator;
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
    public QuotaConfigDto upsertConfig(QuotaConfigRequest request) {
        return upsertConfig(request, null);
    }

    @Transactional
    public QuotaFareDto upsertQuotaFare(QuotaFareRequest request) {
        return upsertQuotaFare(request, null);
    }

    @CacheEvict(cacheNames = CacheNames.FARE_RULES, allEntries = true)
    @Transactional
    public FareRuleDto upsertFareRule(com.yatrarail.dto.admin.FareRuleRequest request) {
        return upsertFareRule(request, null);
    }

    // ------------------------------------------------------------------ quota splits

    @Transactional(readOnly = true)
    public List<QuotaConfigDto> listConfigs() {
        return quotaConfigRepository.findAllByOrderByTrainIdAscSeatClassAsc().stream()
                .map(config -> QuotaConfigDto.from(config, capacityOf(config)))
                .toList();
    }

    @Transactional(readOnly = true)
    public QuotaConfigDto getConfig(Long trainId, String seatClass) {
        QuotaConfig config = requireConfig(trainId, parseSeatClass(seatClass));
        return QuotaConfigDto.from(config, capacityOf(config));
    }

    /**
     * Creates or replaces the split for one (train, class).
     * <p>
     * Rejected when the reserved slices exceed the class's physical capacity, and when the new
     * numbers are smaller than what has already been sold on any journey date.
     */
    @Transactional
    public QuotaConfigDto upsertConfig(QuotaConfigRequest request, Long actorId) {
        SeatClass seatClass = parseSeatClass(request.getSeatClass());
        Train train = trainRepository.findById(request.getTrainId())
                .orElseThrow(() -> new ResourceNotFoundException("Train not found"));

        QuotaConfig config = quotaConfigRepository
                .findByTrainIdAndSeatClass(train.getId(), seatClass)
                .orElseGet(() -> QuotaConfig.builder()
                        .train(train)
                        .seatClass(seatClass)
                        .build());

        int tatkal = nonNegative(request.getTatkalSeats());
        int senior = nonNegative(request.getSeniorSeats());
        int ladies = nonNegative(request.getLadiesSeats());
        int divyang = nonNegative(request.getDivyangSeats());
        int staff = nonNegative(request.getStaffSeats());
        int windowDays = request.getTatkalWindowDays() == null ? 0 : request.getTatkalWindowDays();
        boolean active = !Boolean.FALSE.equals(request.getActive());

        config.setTatkalSeats(tatkal);
        config.setSeniorSeats(senior);
        config.setLadiesSeats(ladies);
        config.setDivyangSeats(divyang);
        config.setStaffSeats(staff);
        config.setTatkalWindowDays(windowDays);
        config.setActive(active);

        long total = coachRepository.sumTotalSeatsByTrainAndClass(train.getId(), seatClass);
        int totalCapacity = (int) Math.max(0L, total);
        if (totalCapacity == 0) {
            throw new BadRequestException("Train " + train.getTrainNumber() + " has no active "
                    + seatClass.getLabel() + " coaches, so a quota split cannot be configured for it");
        }
        int reservedTotal = tatkal + senior + ladies + divyang + staff;
        if (reservedTotal > totalCapacity) {
            throw new BadRequestException("Reserved quotas total " + reservedTotal
                    + " seats but " + seatClass.getLabel() + " only has " + totalCapacity
                    + " seats. The general quota would go negative, so this split was rejected.");
        }
        if (active && tatkal > 0 && windowDays <= 0) {
            throw new BadRequestException("Tatkal seats are set to " + tatkal
                    + " but the departure window is " + windowDays
                    + " days. Set a window of at least 1 day, or set Tatkal seats to 0.");
        }
        if (!active && reservedTotal > 0) {
            log.info("Quota split for train {} class {} saved as inactive with {} reserved seats "
                    + "retained but not enforced", train.getTrainNumber(), seatClass, reservedTotal);
        }

        assertDoesNotBreakExistingBookings(train.getId(), seatClass, config, totalCapacity);

        QuotaConfig saved = quotaConfigRepository.save(config);
        log.info("Quota split updated: train {} class {} -> tatkal {} senior {} ladies {} divyang {} "
                        + "staff {} (general {} of {})", train.getTrainNumber(), seatClass,
                tatkal, senior, ladies, divyang, staff, totalCapacity - reservedTotal, totalCapacity);
        auditService.record(AuditService.QUOTA_UPDATED, "QUOTA_CONFIG",
                saved.getId() == null ? train.getId() : saved.getId(),
                actor(actorId) + " set the " + seatClass + " quota on train " + train.getTrainNumber()
                        + " to tatkal " + tatkal + ", senior " + senior + ", ladies " + ladies
                        + ", divyang " + divyang + ", staff " + staff
                        + " of " + totalCapacity + " seats", actorId);
        return QuotaConfigDto.from(saved, totalCapacity);
    }

    /**
     * Refuses a split that would leave an already-sold seat outside the quota it was sold under.
     * <p>
     * Without this, shrinking a senior-citizen slice from 10 to 2 would leave eight existing
     * bookings claiming a quota with no room - the passenger would be holding a ticket the system
     * itself considers impossible, and any later promotion for them would silently fail.
     */
    private void assertDoesNotBreakExistingBookings(Long trainId, SeatClass seatClass,
                                                    QuotaConfig candidate, int totalCapacity) {
        Map<LocalDate, Map<QuotaType, Integer>> usage = new HashMap<>();
        for (Object[] row : seatReservationRepository.countLiveByJourneyDateAndQuota(
                trainId, seatClass, java.time.LocalDateTime.now())) {
            usage.computeIfAbsent((LocalDate) row[0], k -> new EnumMap<>(QuotaType.class))
                    .merge((QuotaType) row[1], ((Number) row[2]).intValue(), Integer::sum);
        }
        if (usage.isEmpty()) {
            return;
        }

        boolean active = candidate.isEnabled();
        int tatkal = active ? candidate.reservedSeatsFor(QuotaType.TATKAL) : 0;
        int senior = active ? candidate.reservedSeatsFor(QuotaType.SENIOR) : 0;
        int ladies = active ? candidate.reservedSeatsFor(QuotaType.LADIES) : 0;
        int divyang = active ? candidate.reservedSeatsFor(QuotaType.DIVYANG) : 0;
        int staff = active ? candidate.reservedSeatsFor(QuotaType.STAFF) : 0;
        int general = Math.max(0, totalCapacity - (tatkal + senior + ladies + divyang + staff));

        Map<QuotaType, Integer> newCapacity = new EnumMap<>(QuotaType.class);
        newCapacity.put(QuotaType.GENERAL, general);
        newCapacity.put(QuotaType.TATKAL, tatkal);
        newCapacity.put(QuotaType.SENIOR, senior);
        newCapacity.put(QuotaType.LADIES, ladies);
        newCapacity.put(QuotaType.DIVYANG, divyang);
        newCapacity.put(QuotaType.STAFF, staff);

        for (Map.Entry<LocalDate, Map<QuotaType, Integer>> dateEntry : usage.entrySet()) {
            for (Map.Entry<QuotaType, Integer> entry : dateEntry.getValue().entrySet()) {
                int sold = entry.getValue();
                int allowed = newCapacity.getOrDefault(entry.getKey(), 0);
                if (sold > allowed) {
                    throw new ConflictException("Cannot reduce the " + entry.getKey().getLabel()
                            + " quota to " + allowed + " seats: " + sold + " seats are already booked "
                            + "under it on " + dateEntry.getKey()
                            + ". Cancel or move those bookings first.");
                }
            }
        }
    }

    // ------------------------------------------------------------------ concessions

    @Transactional(readOnly = true)
    public List<QuotaFareDto> listQuotaFares() {
        return quotaFareRepository.findAllByOrderBySeatClassAscQuotaTypeAsc().stream()
                .map(QuotaFareDto::from)
                .toList();
    }

    @Transactional
    public QuotaFareDto upsertQuotaFare(QuotaFareRequest request, Long actorId) {
        SeatClass seatClass = parseSeatClass(request.getSeatClass());
        QuotaType quotaType = parseQuota(request.getQuota());
        if (quotaType == QuotaType.GENERAL) {
            throw new BadRequestException("The general quota is charged at full fare and cannot be discounted");
        }

        QuotaFare fare = quotaFareRepository.findBySeatClassAndQuotaType(seatClass, quotaType)
                .orElseGet(() -> QuotaFare.builder()
                        .seatClass(seatClass)
                        .quotaType(quotaType)
                        .build());
        fare.setDiscountPercent(nonNegative(request.getDiscountPercent()));
        fare.setNote(request.getNote() == null || request.getNote().isBlank() ? null : request.getNote().trim());
        QuotaFare saved = quotaFareRepository.save(fare);
        auditService.record(AuditService.QUOTA_FARE_UPDATED, "QUOTA_FARE", saved.getId(),
                actor(actorId) + " set the " + quotaType + " concession for " + seatClass
                        + " to " + saved.getDiscountPercent() + "%", actorId);
        return QuotaFareDto.from(saved);
    }

    // ------------------------------------------------------------------ fare rules

    @Transactional(readOnly = true)
    public List<FareRuleDto> listFareRules() {
        return fareRuleRepository.findAll().stream()
                .sorted(java.util.Comparator.comparing(rule -> rule.getSeatClass().name()))
                .map(FareRuleDto::from)
                .toList();
    }

    @CacheEvict(cacheNames = CacheNames.FARE_RULES, allEntries = true)
    @Transactional
    public FareRuleDto upsertFareRule(com.yatrarail.dto.admin.FareRuleRequest request, Long actorId) {
        SeatClass seatClass = parseSeatClass(request.getSeatClass());
        FareRule rule = fareRuleRepository.findBySeatClass(seatClass)
                .orElseGet(() -> FareRule.builder().seatClass(seatClass).build());
        rule.setFarePerKm(request.getFarePerKm());
        rule.setMinimumFare(request.getMinimumFare());
        rule.setPlatformFee(request.getPlatformFee() == null ? java.math.BigDecimal.ZERO : request.getPlatformFee());
        rule.setGstPercent(request.getGstPercent() == null ? java.math.BigDecimal.ZERO : request.getGstPercent());
        FareRule saved = fareRuleRepository.save(rule);
        auditService.record(AuditService.FARE_RULE_UPDATED, "FARE_RULE", saved.getId(),
                actor(actorId) + " set the " + seatClass + " tariff to "
                        + saved.getFarePerKm() + "/km, minimum " + saved.getMinimumFare()
                        + ", platform fee " + saved.getPlatformFee() + ", GST " + saved.getGstPercent() + "%",
                actorId);
        return FareRuleDto.from(saved);
    }

    // ------------------------------------------------------------------ preview

    /**
     * Runs the production pricing code against a hypothetical journey.
     * <p>
     * This is a pricing tool, not a booking tool: it takes no lock, touches no inventory and creates
     * nothing, so an administrator can safely check the effect of a fare or quota edit on a real
     * route. Quotas are resolved optimistically (as if a seat were free), because the point is to
     * inspect the fare, and the concession a passenger would actually get is shown per line.
     */
    @Transactional(readOnly = true)
    public FareBreakdownDto previewFare(FarePreviewRequest request) {
        Coach coach = coachRepository.findById(request.getCoachId())
                .orElseThrow(() -> new ResourceNotFoundException("Coach not found"));
        Train train = trainRepository.findById(request.getTrainId())
                .orElseThrow(() -> new ResourceNotFoundException("Train not found"));
        if (!coach.getTrain().getId().equals(train.getId())) {
            throw new BadRequestException("Selected class does not belong to this train");
        }
        int distanceKm = trainScheduleValidator
                .resolveSegment(train, request.getSourceStationId(), request.getDestinationStationId())
                .distanceKm();

        SeatClass seatClass = coach.getSeatClass();
        QuotaService.QuotaSnapshot snapshot = quotaService.snapshot(coach, request.getJourneyDate());
        QuotaService.QuotaSnapshot budget = snapshot.copy();

        List<PassengerFare> fares = new ArrayList<>();
        for (PassengerDto passenger : request.getPassengers()) {
            QuotaType requested = QuotaService.parse(passenger.getQuota());
            // A preview must never show a concession the booking itself would refuse, so the same
            // eligibility gate the booking path uses runs here. The caller is an administrator,
            // which is what lets a Staff-quota preview through.
            quotaService.validateEligibility(requested, passenger, RoleName.ADMIN, budget);
            QuotaService.QuotaDecision decision = quotaService.decide(requested, budget, true);
            budget.consume(decision.quota());
            fares.add(PassengerFare.of(passenger.getFullName(), passenger.getAge(), passenger.getGender(),
                    decision.quota(), quotaService.discountFor(seatClass, decision.quota()),
                    decision.note()));
        }

        FareCalculationService.Tariff tariff = fareCalculationService.resolveTariff(seatClass);
        FareBreakdownDto breakdown = fareCalculationService.buildBreakdown(
                coach, distanceKm, distanceKm, fares, tariff);
        breakdown.getNotes().add("Preview only: no booking or seat hold was created. Each line is priced "
                + "as if a seat were free, so a quota that is currently full may still show its "
                + "concessionary fare here.");
        return breakdown;
    }

    // ------------------------------------------------------------------ helpers

    private QuotaConfig requireConfig(Long trainId, SeatClass seatClass) {
        return quotaConfigRepository.findByTrainIdAndSeatClass(trainId, seatClass)
                .orElseThrow(() -> new ResourceNotFoundException("No quota configuration exists for train "
                        + trainId + " in " + seatClass.getLabel()));
    }

    private int capacityOf(QuotaConfig config) {
        long total = coachRepository.sumTotalSeatsByTrainAndClass(
                config.getTrain().getId(), config.getSeatClass());
        return (int) Math.max(0L, total);
    }

    private static int nonNegative(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }

    static SeatClass parseSeatClass(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BadRequestException("Seat class is required");
        }
        try {
            return SeatClass.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("Unknown seat class: " + raw);
        }
    }

    private static QuotaType parseQuota(String raw) {
        try {
            return QuotaService.parse(raw);
        } catch (BadRequestException ex) {
            throw new BadRequestException(ex.getMessage());
        }
    }
}

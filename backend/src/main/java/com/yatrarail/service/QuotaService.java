package com.yatrarail.service;

import com.yatrarail.dto.booking.PassengerDto;
import com.yatrarail.dto.quota.QuotaAvailabilityDto;
import com.yatrarail.entity.*;
import com.yatrarail.exception.BadRequestException;
import com.yatrarail.exception.ForbiddenException;
import com.yatrarail.repository.CoachRepository;
import com.yatrarail.repository.QuotaConfigRepository;
import com.yatrarail.repository.QuotaFareRepository;
import com.yatrarail.repository.SeatReservationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Decides which quota a passenger travels under, and whether that quota has room.
 * <p>
 * A quota is a <i>slice of one class's inventory on one journey date</i>, so the whole service is
 * built around a single idea: the class's physical capacity is divided among the quotas.
 * <pre>
 *   general = total capacity − (tatkal? + senior + ladies + divyang + staff)
 * </pre>
 * Only the reserved slices are configured. The general slice is derived, which is what makes the
 * configuration self-consistent: an administrator cannot save a split that adds up to more seats
 * than the train owns, and closing a coach automatically widens the general quota.
 * <p>
 * The Tatkal-sim slice is the one time-dependent piece: outside its departure window it does not
 * exist yet, so those seats belong to general until the window opens, and a Tatkal request is
 * rejected outright rather than silently ignored.
 * <p>
 * Usage is counted from {@link SeatReservation}, not from the booking rows, because a reservation
 * <i>is</i> the claim on a seat: an unpaid hold already occupies its quota and releasing the
 * reservation releases the quota. Everything the allocation engine needs is in a single
 * {@link QuotaSnapshot} read under the coach lock, so the budget it spends from is a consistent
 * picture taken at the moment of the decision.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuotaService {

    private final QuotaConfigRepository quotaConfigRepository;
    private final QuotaFareRepository quotaFareRepository;
    private final CoachRepository coachRepository;
    private final SeatReservationRepository seatReservationRepository;

    // ------------------------------------------------------------------ reading the split

    @Transactional(readOnly = true)
    public QuotaSnapshot snapshot(Coach coach, LocalDate journeyDate) {
        Long trainId = coach.getTrain().getId();
        SeatClass seatClass = coach.getSeatClass();
        QuotaConfig config = quotaConfigRepository.findByTrainIdAndSeatClass(trainId, seatClass).orElse(null);

        // A (train, class) with no configuration is simply an unquota'd class: everything is general.
        boolean configured = config != null;
        boolean active = configured && !Boolean.FALSE.equals(config.getActive());
        int tatkalSeats = active ? config.reservedSeatsFor(QuotaType.TATKAL) : 0;
        int seniorSeats = active ? config.reservedSeatsFor(QuotaType.SENIOR) : 0;
        int ladiesSeats = active ? config.reservedSeatsFor(QuotaType.LADIES) : 0;
        int divyangSeats = active ? config.reservedSeatsFor(QuotaType.DIVYANG) : 0;
        int staffSeats = active ? config.reservedSeatsFor(QuotaType.STAFF) : 0;
        int windowDays = config == null || config.getTatkalWindowDays() == null ? 0 : config.getTatkalWindowDays();

        boolean tatkalOpen = active && tatkalSeats > 0 && isTatkalOpen(journeyDate, windowDays);
        // Outside its window the Tatkal slice is not carved out at all - those seats are general.
        int effectiveTatkal = tatkalOpen ? tatkalSeats : 0;

        long total = coachRepository.sumTotalSeatsByTrainAndClass(trainId, seatClass);
        int totalCapacity = (int) Math.max(0L, total);
        int reservedTotal = effectiveTatkal + seniorSeats + ladiesSeats + divyangSeats + staffSeats;
        int generalCapacity = Math.max(0, totalCapacity - reservedTotal);

        Map<QuotaType, Integer> capacity = new EnumMap<>(QuotaType.class);
        capacity.put(QuotaType.GENERAL, generalCapacity);
        capacity.put(QuotaType.TATKAL, effectiveTatkal);
        capacity.put(QuotaType.SENIOR, seniorSeats);
        capacity.put(QuotaType.LADIES, ladiesSeats);
        capacity.put(QuotaType.DIVYANG, divyangSeats);
        capacity.put(QuotaType.STAFF, staffSeats);

        Map<QuotaType, Integer> used = new EnumMap<>(QuotaType.class);
        for (QuotaType quota : QuotaType.values()) {
            used.put(quota, 0);
        }
        for (Object[] row : seatReservationRepository.countLiveByQuota(
                trainId, seatClass, journeyDate, java.time.LocalDateTime.now())) {
            used.put((QuotaType) row[0], ((Number) row[1]).intValue());
        }

        return new QuotaSnapshot(trainId, seatClass, journeyDate, totalCapacity, configured, active,
                windowDays, tatkalOpen, capacity, used);
    }

    /**
     * The Tatkal-sim window, measured in whole days before the journey date.
     * {@code windowDays = 1} means the quota is open today and tomorrow; {@code 0} means the
     * feature is switched off for this class. The comparison is inclusive of {@code windowDays}
     * itself, so "1 day before departure" is the last day the quota can be used.
     */
    private static boolean isTatkalOpen(LocalDate journeyDate, int windowDays) {
        if (windowDays <= 0) {
            return false;
        }
        long days = ChronoUnit.DAYS.between(LocalDate.now(), journeyDate);
        return days >= 0 && days <= windowDays;
    }

    // ------------------------------------------------------------------ eligibility

    /**
     * Rejects a quota the passenger is not entitled to. This is a hard error, never a silent
     * downgrade: a passenger who ticks "Senior Citizen" at 34 gets told why, and does not
     * accidentally get a full-fare general ticket.
     * <p>
     * This covers both kinds of gate: the personal {@code requiresEligibility} rules and the
     * {@code timeGated} departure window, so the guard is "not GENERAL" rather than
     * {@code requiresEligibility} - otherwise the Tatkal window check below would be unreachable.
     */
    public void validateEligibility(QuotaType requested, PassengerDto passenger, RoleName actorRole,
                                   QuotaSnapshot snapshot) {
        if (requested == null || requested == QuotaType.GENERAL) {
            return;
        }
        switch (requested) {
            case SENIOR -> {
                if (passenger.getAge() == null) {
                    throw new BadRequestException("Passenger age is required to travel under the Senior Citizen quota");
                }
                if (passenger.getAge() < QuotaType.SENIOR_MIN_AGE) {
                    throw new BadRequestException(passenger.getFullName() + " is "
                            + passenger.getAge() + " years old and is not eligible for the Senior Citizen quota, "
                            + "which requires age " + QuotaType.SENIOR_MIN_AGE + " or above");
                }
            }
            case LADIES -> {
                if (!isFemale(passenger.getGender())) {
                    throw new BadRequestException(passenger.getFullName()
                            + " is not eligible for the Ladies quota, which is restricted to female passengers");
                }
            }
            case DIVYANG -> {
                if (!Boolean.TRUE.equals(passenger.getDisabilityCertified())) {
                    throw new BadRequestException(passenger.getFullName()
                            + " is not eligible for the Divyang quota: a disability certificate must be declared");
                }
            }
            case STAFF -> {
                if (actorRole != RoleName.STAFF && actorRole != RoleName.ADMIN) {
                    throw new ForbiddenException("The Staff quota can only be used by a verified staff account");
                }
            }
            case TATKAL -> {
                if (!snapshot.isTatkalOpen()) {
                    throw new BadRequestException("The Tatkal (simulated) quota is not open for this journey");
                }
                if (snapshot.capacityOf(QuotaType.TATKAL) == 0) {
                    throw new BadRequestException("No Tatkal (simulated) quota is configured for this class");
                }
            }
            default -> {
            }
        }
    }

    private static boolean isFemale(String gender) {
        if (gender == null) {
            return false;
        }
        String value = gender.trim().toUpperCase(Locale.ROOT);
        return "FEMALE".equals(value) || "F".equals(value) || "WOMAN".equals(value);
    }

    /** Parses a client-supplied quota string, defaulting to {@link QuotaType#GENERAL}. */
    public static QuotaType parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return QuotaType.GENERAL;
        }
        try {
            return QuotaType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("Unknown booking quota: " + raw);
        }
    }

    // ------------------------------------------------------------------ the allocation decision

    /**
     * Decides the quota a passenger is actually booked under, and how much of that quota the booking
     * has left afterwards.
     * <p>
     * The rule is deliberately boring and always disclosed to the passenger:
     * <ol>
     *   <li>no quota configured or no room in the requested one → {@link QuotaType#GENERAL}, if
     *       general still has a seat;</li>
     *   <li>general is full too → stay on the <i>requested</i> quota, because a RAC or waitlist entry
     *       is a claim on that quota and must not be silently upgraded to a different one;</li>
     *   <li>general has room and the requested quota is a reserved one → {@link QuotaType#GENERAL} at
     *       the full fare, with a note explaining the substitution.</li>
     * </ol>
     * A confirmed seat always consumes its own quota, so the number of confirmed seats a quota can
     * hand out is exactly its capacity. RAC and waitlist entries do not consume quota until they are
     * promoted, so they never block a passenger who could still get a seat.
     */
    public QuotaDecision decide(QuotaType requested, QuotaSnapshot snapshot, boolean seatAvailable) {
        QuotaType requestedQuota = requested == null ? QuotaType.GENERAL : requested;
        int generalRemaining = snapshot.remainingOf(QuotaType.GENERAL);

        if (requestedQuota == QuotaType.GENERAL) {
            return new QuotaDecision(QuotaType.GENERAL, false, null, generalRemaining);
        }
        if (snapshot.capacityOf(requestedQuota) == 0) {
            // The class is not quota-managed for this quota: general is the only option.
            return new QuotaDecision(QuotaType.GENERAL, false,
                    "No " + requestedQuota.getLabel() + " quota is configured for this class, so the "
                            + "general fare applies", generalRemaining);
        }
        if (seatAvailable && snapshot.remainingOf(requestedQuota) > 0) {
            return new QuotaDecision(requestedQuota, false, null, generalRemaining);
        }
        if (generalRemaining > 0) {
            return new QuotaDecision(QuotaType.GENERAL, true,
                    "The " + requestedQuota.getLabel() + " quota is full for this journey, so this "
                            + "passenger is booked under the general quota at the full fare", generalRemaining);
        }
        // Nothing anywhere: keep the requested quota so the later promotion serves the right queue.
        return new QuotaDecision(requestedQuota, false,
                "No seats are available; " + requestedQuota.getLabel()
                        + " quota has been requested and will apply if a seat is released",
                generalRemaining);
    }

    /** Charges the decision's quota against the snapshot, returning the snapshot for further calls. */
    public QuotaSnapshot consume(QuotaSnapshot snapshot, QuotaType quota) {
        snapshot.consume(quota);
        return snapshot;
    }

    // ------------------------------------------------------------------ read models

    @Transactional(readOnly = true)
    public List<QuotaAvailabilityDto> availability(Coach coach, LocalDate journeyDate) {
        QuotaSnapshot snapshot = snapshot(coach, journeyDate);
        List<QuotaAvailabilityDto> out = new ArrayList<>();
        for (QuotaType quota : QuotaType.values()) {
            int capacity = snapshot.capacityOf(quota);
            int used = snapshot.usedOf(quota);
            out.add(QuotaAvailabilityDto.builder()
                    .quota(quota.name())
                    .label(quota.getLabel())
                    .description(quota.getDescription())
                    .capacity(capacity)
                    .used(used)
                    .remaining(Math.max(0, capacity - used))
                    .reserved(quota.isReserved())
                    .open(isUsable(snapshot, quota))
                    .unavailableReason(unavailableReason(snapshot, quota))
                    .build());
        }
        return out;
    }

    private static boolean isUsable(QuotaSnapshot snapshot, QuotaType quota) {
        if (quota == QuotaType.TATKAL) {
            return snapshot.isTatkalOpen();
        }
        return true;
    }

    private static String unavailableReason(QuotaSnapshot snapshot, QuotaType quota) {
        if (!isUsable(snapshot, quota)) {
            return "The Tatkal (simulated) quota only opens " + snapshot.getTatkalWindowDays()
                    + " day(s) before departure";
        }
        if (snapshot.capacityOf(quota) == 0) {
            return quota == QuotaType.GENERAL
                    ? "Every seat in this class is reserved under other quotas"
                    : "No seats are reserved for this quota in this class";
        }
        if (snapshot.remainingOf(quota) == 0) {
            return "This quota is full for this journey";
        }
        return null;
    }

    // ------------------------------------------------------------------ concessions

    /** The concession for a (class, quota) pair, defaulting to 0 when none is configured. */
    @Transactional(readOnly = true)
    public int discountFor(SeatClass seatClass, QuotaType quota) {
        if (quota == null || quota == QuotaType.GENERAL) {
            return 0;
        }
        return quotaFareRepository.findBySeatClassAndQuotaType(seatClass, quota)
                .map(f -> f.getDiscountPercent() == null ? 0 : f.getDiscountPercent())
                .orElse(0);
    }

    // ------------------------------------------------------------------ snapshot type

    /**
     * One consistent picture of a class's quota position on one journey date, read under the same
     * coach lock that guards the allocation. The maps are mutable on purpose: {@link #consume} lets
     * the allocation loop spend from one snapshot instead of re-querying per passenger.
     */
    public static final class QuotaSnapshot {

        private final Long trainId;
        private final SeatClass seatClass;
        private final LocalDate journeyDate;
        private final int totalCapacity;
        private final boolean configured;
        private final boolean active;
        private final int tatkalWindowDays;
        private final boolean tatkalOpen;
        private final Map<QuotaType, Integer> capacity;
        private final Map<QuotaType, Integer> used;

        private QuotaSnapshot(Long trainId, SeatClass seatClass, LocalDate journeyDate, int totalCapacity,
                              boolean configured, boolean active, int tatkalWindowDays, boolean tatkalOpen,
                              Map<QuotaType, Integer> capacity, Map<QuotaType, Integer> used) {
            this.trainId = trainId;
            this.seatClass = seatClass;
            this.journeyDate = journeyDate;
            this.totalCapacity = totalCapacity;
            this.configured = configured;
            this.active = active;
            this.tatkalWindowDays = tatkalWindowDays;
            this.tatkalOpen = tatkalOpen;
            this.capacity = capacity;
            this.used = used;
        }

        public int capacityOf(QuotaType quota) {
            return capacity.getOrDefault(quota, 0);
        }

        public int usedOf(QuotaType quota) {
            return used.getOrDefault(quota, 0);
        }

        public int remainingOf(QuotaType quota) {
            return Math.max(0, capacityOf(quota) - usedOf(quota));
        }

        public void consume(QuotaType quota) {
            used.merge(quota, 1, Integer::sum);
        }

        /**
         * A detached copy, so a caller can spend from a budget (the quote pricing several
         * passengers) without mutating the snapshot other callers are still reading.
         */
        public QuotaSnapshot copy() {
            return new QuotaSnapshot(trainId, seatClass, journeyDate, totalCapacity, configured, active,
                    tatkalWindowDays, tatkalOpen, new EnumMap<>(capacity), new EnumMap<>(used));
        }

        public Long getTrainId() {
            return trainId;
        }

        public SeatClass getSeatClass() {
            return seatClass;
        }

        public LocalDate getJourneyDate() {
            return journeyDate;
        }

        public int getTotalCapacity() {
            return totalCapacity;
        }

        public boolean isConfigured() {
            return configured;
        }

        public boolean isActive() {
            return active;
        }

        public int getTatkalWindowDays() {
            return tatkalWindowDays;
        }

        public boolean isTatkalOpen() {
            return tatkalOpen;
        }
    }

    /** The outcome of one allocation decision. */
    public record QuotaDecision(QuotaType quota, boolean fellBack, String note, int generalRemaining) {
    }
}

package com.yatrarail.service;

import com.yatrarail.dto.fare.FareBreakdownDto;
import com.yatrarail.dto.fare.FarePassengerLineDto;
import com.yatrarail.entity.Booking;
import com.yatrarail.entity.BookingPassenger;
import com.yatrarail.entity.Coach;
import com.yatrarail.entity.FareRule;
import com.yatrarail.entity.QuotaType;
import com.yatrarail.entity.SeatClass;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Simplified, documented demo fare model:
 * <pre>fare per passenger = max(coach.baseFare, fareRule.farePerKm * distanceKm, fareRule.minimumFare)</pre>
 * This is an academic approximation and does not reflect an official Indian Railways tariff.
 * <p>
 * <b>Phase 2 change:</b> {@code distanceKm} is now the distance of the <i>booked segment</i>
 * (boarding → alighting) rather than the train's full route length. Booking New Delhi → Lucknow on a
 * train that also runs on to Howrah is charged for the New Delhi → Lucknow distance only.
 * <p>
 * The {@code minimumFare} floor stays in place, which is why a very short segment can still return
 * the class minimum.
 * <p>
 * <b>Phase 4 change:</b> a single fare number is replaced by a full {@link FareBreakdownDto}. Three
 * separate things are made explicit, because each has a different owner and a different reason to
 * change:
 * <ol>
 *   <li><b>the pricing basis</b> - which of the three candidates above actually set the fare;</li>
 *   <li><b>per-passenger concessions</b> - the quota a passenger is booked under and the percentage
 *       taken off, applied to the quota they are <i>actually</i> under, not the one they asked for;</li>
 *   <li><b>booking-level charges</b> - a flat platform fee and GST on the discounted passenger
 *       subtotal.</li>
 * </ol>
 * The whole breakdown is computed from one {@link Tariff} snapshot, so a tariff edit landing halfway
 * through a booking can never produce an internally inconsistent quote. Money is rounded to paise
 * exactly once, at the end of each total, so the displayed lines always add up to the displayed
 * grand total.
 */
@Service
@RequiredArgsConstructor
public class FareCalculationService {

    /**
     * Phase 10: fare rules are read through {@link FareRuleService} so the per-class tariff lookup
     * is served from cache on the hot path. Only positive lookups are used here, so the service's
     * {@code Optional} is unwrapped at the call sites below.
     */
    private final FareRuleService fareRuleService;

    // ------------------------------------------------------------------ Phase 2 / 3 API

    public BigDecimal calculateFarePerPassenger(Coach coach, int distanceKm) {
        int distance = Math.max(0, distanceKm);
        BigDecimal fare = coach.getBaseFare();
        FareRule rule = fareRuleService.find(coach.getSeatClass()).orElse(null);
        if (rule != null) {
            BigDecimal distanceFare = rule.getFarePerKm().multiply(BigDecimal.valueOf(distance));
            fare = fare.max(distanceFare).max(rule.getMinimumFare());
        }
        return fare.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Fare for the whole booking: per-passenger segment fare × number of passengers.
     * <p>
     * Phase 4 note: this remains the <i>gross</i> figure (before quota concessions, platform fee and
     * GST). The charged total is {@link #buildBreakdown}'s {@code totalPayable}, which is what the
     * booking now stores in {@code totalFare}. Kept because the search results page shows the
     * starting fare of a class, which is a gross "from" price.
     */
    public BigDecimal calculateTotalFare(Coach coach, int distanceKm, int passengers) {
        if (passengers <= 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return calculateFarePerPassenger(coach, distanceKm)
                .multiply(BigDecimal.valueOf(passengers))
                .setScale(2, RoundingMode.HALF_UP);
    }

    // ------------------------------------------------------------------ Phase 4 API

    /**
     * One consistent read of a class's tariff. The booking stores these five numbers rather than a
     * foreign key, so a later tariff change cannot retroactively alter what a ticket says it cost.
     */
    public Tariff resolveTariff(SeatClass seatClass) {
        FareRule rule = fareRuleService.find(seatClass).orElse(null);
        if (rule == null) {
            // No configured tariff for the class: no floor, no charges. The coach's own base fare
            // still applies, so a class without a rule is simply priced by its coach.
            return new Tariff(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, false);
        }
        return new Tariff(
                nullSafe(rule.getFarePerKm()),
                nullSafe(rule.getMinimumFare()),
                nullSafe(rule.getPlatformFee()),
                nullSafe(rule.getGstPercent()),
                true);
    }

    /**
     * Builds the itemised fare for a set of passengers.
     * <p>
     * {@code passengerFares} carries the per-passenger decisions the quota engine already made -
     * which quota the passenger is really under and therefore which concession applies - so this
     * method stays purely arithmetic and cannot disagree with the seats that were handed out.
     */
    public FareBreakdownDto buildBreakdown(Coach coach, int distanceKm, int bookedDistanceKm,
                                            List<PassengerFare> passengerFares, Tariff tariff) {
        int distance = Math.max(0, distanceKm);
        BigDecimal coachBaseFare = money(coach.getBaseFare());
        BigDecimal distanceFare = money(tariff.farePerKm().multiply(BigDecimal.valueOf(distance)));
        BigDecimal minimumFare = money(tariff.minimumFare());

        BigDecimal gross = coachBaseFare;
        String basis = FareBasis.COACH_BASE;
        if (distanceFare.compareTo(gross) > 0) {
            gross = distanceFare;
            basis = FareBasis.DISTANCE;
        }
        if (minimumFare.compareTo(gross) > 0) {
            gross = minimumFare;
            basis = FareBasis.MINIMUM;
        }
        gross = money(gross);

        List<FarePassengerLineDto> lines = new ArrayList<>();
        BigDecimal grossTotal = ZERO;
        BigDecimal discountTotal = ZERO;
        for (PassengerFare passenger : passengerFares) {
            int discount = clampDiscount(passenger.discountPercent());
            BigDecimal lineGross = gross;
            BigDecimal lineDiscount = money(gross.multiply(BigDecimal.valueOf(discount))
                    .divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP));
            BigDecimal payable = money(lineGross.subtract(lineDiscount));
            lines.add(FarePassengerLineDto.builder()
                    .fullName(passenger.fullName())
                    .age(passenger.age())
                    .gender(passenger.gender())
                    .quota(passenger.quota().name())
                    .quotaLabel(passenger.quota().getLabel())
                    .discountPercent(discount)
                    .grossFare(lineGross)
                    .discountAmount(lineDiscount)
                    .payableFare(payable)
                    .quotaNote(passenger.quotaNote())
                    .build());
            grossTotal = grossTotal.add(lineGross);
            discountTotal = discountTotal.add(lineDiscount);
        }

        grossTotal = money(grossTotal);
        discountTotal = money(discountTotal);
        BigDecimal passengerFareTotal = money(grossTotal.subtract(discountTotal));
        BigDecimal platformFee = money(tariff.platformFee());
        BigDecimal gstPercent = money(tariff.gstPercent());
        BigDecimal gstAmount = money(passengerFareTotal
                .multiply(gstPercent)
                .divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP));
        BigDecimal totalPayable = money(passengerFareTotal.add(platformFee).add(gstAmount));

        return FareBreakdownDto.builder()
                .currency(FareBreakdownDto.CURRENCY)
                .coachId(coach.getId())
                .coachCode(coach.getCoachCode())
                .seatClass(coach.getSeatClass().name())
                .seatClassLabel(coach.getSeatClass().getLabel())
                .distanceKm(bookedDistanceKm)
                .coachBaseFare(coachBaseFare)
                .farePerKm(tariff.farePerKm())
                .distanceFare(distanceFare)
                .minimumFare(minimumFare)
                .fareBasis(basis)
                .grossFarePerPassenger(gross)
                .passengers(lines)
                .grossFareTotal(grossTotal)
                .discountTotal(discountTotal)
                .passengerFareTotal(passengerFareTotal)
                .platformFee(platformFee)
                .gstPercent(gstPercent)
                .gstAmount(gstAmount)
                .totalPayable(totalPayable)
                .notes(notes(coach, basis, tariff, distance))
                .build();
    }

    private static List<String> notes(Coach coach, String basis, Tariff tariff, int distanceKm) {
        List<String> notes = new ArrayList<>();
        notes.add("Simulated YatraRail tariff for academic demonstration - not an official Indian Railways fare.");
        notes.add(switch (basis) {
            case FareBasis.DISTANCE -> "Fare derived from " + tariff.farePerKm()
                    + " per km over the " + distanceKm + " km booked segment.";
            case FareBasis.MINIMUM -> "Distance fare was lower than the "
                    + coach.getSeatClass().getLabel() + " minimum fare of " + money(tariff.minimumFare())
                    + ", so the minimum was charged.";
            default -> "Coach base fare of " + money(coach.getBaseFare())
                    + " applied: the distance fare did not exceed it.";
        });
        if (tariff.gstPercent().signum() > 0) {
            notes.add("GST at " + tariff.gstPercent() + "% is applied to the passenger fare subtotal. "
                    + "The platform fee is not taxed.");
        }
        if (tariff.platformFee().signum() > 0) {
            notes.add("A flat platform fee of " + tariff.platformFee() + " is charged once per booking.");
        }
        return notes;
    }

    /**
     * Refund tiers based on how far ahead of the journey date the cancellation happens:
     * 7+ days: 90%, 2-6 days: 50%, less than 2 days: 25%.
     * <p>
     * Phase 4: the percentage applies to what was actually charged, i.e.
     * {@code booking.totalFare} now includes the platform fee and GST. The platform fee is a
     * convenience charge rather than fare, so it is always returned in full and the tiered
     * percentage is applied only to the passenger fare.
     */
    public BigDecimal calculateRefund(BigDecimal totalFare, long daysBeforeJourney) {
        BigDecimal percentage;
        if (daysBeforeJourney >= 7) {
            percentage = new BigDecimal("0.90");
        } else if (daysBeforeJourney >= 2) {
            percentage = new BigDecimal("0.50");
        } else {
            percentage = new BigDecimal("0.25");
        }
        return money(totalFare.multiply(percentage));
    }

    /**
     * The same tiers, but with the non-refundable platform fee excluded from the base.
     *
     * @param totalFare        the amount charged (passenger fares + platform fee + GST)
     * @param platformFee      the flat fee carried on the booking
     * @param daysBeforeJourney days until departure
     */
    public BigDecimal calculateRefund(BigDecimal totalFare, BigDecimal platformFee, long daysBeforeJourney) {
        BigDecimal fee = platformFee == null ? BigDecimal.ZERO : platformFee.max(BigDecimal.ZERO);
        BigDecimal refundable = totalFare.subtract(fee).max(BigDecimal.ZERO);
        BigDecimal percentage;
        if (daysBeforeJourney >= 7) {
            percentage = new BigDecimal("0.90");
        } else if (daysBeforeJourney >= 2) {
            percentage = new BigDecimal("0.50");
        } else {
            percentage = new BigDecimal("0.25");
        }
        return money(refundable.multiply(percentage).add(fee));
    }

    // ------------------------------------------------------------------ snapshot ↔ entity

    /**
     * Copies a freshly computed breakdown onto the booking as its immutable fare snapshot.
     * <p>
     * The booking deliberately stores the five tariff inputs and every intermediate total rather
     * than a foreign key to {@code fare_rules}: a ticket must still be able to explain its own price
     * after an administrator changes the tariff, and {@code totalFare} is the grand total actually
     * charged.
     */
    public void applySnapshot(Booking booking, FareBreakdownDto breakdown) {
        booking.setFareBase(breakdown.getCoachBaseFare());
        booking.setFarePerKm(breakdown.getFarePerKm());
        booking.setFareMinimum(breakdown.getMinimumFare());
        booking.setDistanceFare(breakdown.getDistanceFare());
        booking.setFareBasis(breakdown.getFareBasis());
        booking.setPassengerFareTotal(breakdown.getPassengerFareTotal());
        booking.setDiscountTotal(breakdown.getDiscountTotal());
        booking.setPlatformFee(breakdown.getPlatformFee());
        booking.setGstPercent(breakdown.getGstPercent());
        booking.setGstAmount(breakdown.getGstAmount());
        booking.setTotalFare(breakdown.getTotalPayable());
    }

    /**
     * Rebuilds the itemised fare <i>stored on a booking</i>, without consulting the current tariff.
     * <p>
     * Reading the snapshot back rather than re-pricing is the whole point of Phase 4: a passenger
     * looking at a ticket in two years must see what they actually paid, not what the rules say
     * today. Bookings created before Phase 4 have no snapshot, so they fall back to the Phase 2/3
     * model ({@code farePerPassenger × passengers}, no charges) and remain readable.
     */
    public FareBreakdownDto rebuildFromSnapshot(Booking booking) {
        Coach coach = booking.getCoach();
        int count = Math.max(1, booking.getPassengers() == null ? 1 : booking.getPassengers().size());
        // {@code fareBasis} is the discriminator, not the money columns: {@link #applySnapshot} always
        // writes it, whereas a pre-Phase-4 row can carry a non-null {@code passengerFareTotal} from
        // the backfill and a legitimate Phase 4 row can carry a zero total (a fully discounted fare).
        boolean hasSnapshot = booking.getFareBasis() != null;

        if (!hasSnapshot) {
            // Pre-Phase-4 booking: gross fare × passengers, no concessions and no charges.
            BigDecimal gross = booking.getFarePerPassenger() == null
                    ? BigDecimal.ZERO : money(booking.getFarePerPassenger());
            BigDecimal grossTotal = money(gross.multiply(BigDecimal.valueOf(count)));
            List<FarePassengerLineDto> lines = new ArrayList<>();
            if (booking.getPassengers() != null) {
                for (BookingPassenger passenger : booking.getPassengers()) {
                    lines.add(FarePassengerLineDto.builder()
                            .fullName(passenger.getFullName())
                            .age(passenger.getAge())
                            .gender(passenger.getGender())
                            .quota(QuotaType.GENERAL.name())
                            .quotaLabel(QuotaType.GENERAL.getLabel())
                            .discountPercent(0)
                            .grossFare(gross)
                            .discountAmount(BigDecimal.ZERO.setScale(2))
                            .payableFare(gross)
                            .build());
                }
            }
            return FareBreakdownDto.builder()
                    .currency(FareBreakdownDto.CURRENCY)
                    .coachId(coach.getId())
                    .coachCode(coach.getCoachCode())
                    .seatClass(coach.getSeatClass().name())
                    .seatClassLabel(coach.getSeatClass().getLabel())
                    .distanceKm(booking.getDistanceKm() == null ? 0 : booking.getDistanceKm())
                    .coachBaseFare(coach.getBaseFare())
                    .grossFarePerPassenger(gross)
                    .fareBasis(FareBasis.COACH_BASE)
                    .passengers(lines)
                    .grossFareTotal(grossTotal)
                    .discountTotal(BigDecimal.ZERO.setScale(2))
                    .passengerFareTotal(grossTotal)
                    .platformFee(BigDecimal.ZERO.setScale(2))
                    .gstPercent(BigDecimal.ZERO.setScale(2))
                    .gstAmount(BigDecimal.ZERO.setScale(2))
                    .totalPayable(money(booking.getTotalFare()))
                    .notes(List.of("Legacy fare: this booking predates the itemised tariff and carries "
                            + "no quota concession, platform fee or GST."))
                    .build();
        }

        BigDecimal gross = maxOf(booking.getFareBase(), booking.getDistanceFare(), booking.getFareMinimum());
        List<FarePassengerLineDto> lines = new ArrayList<>();
        BigDecimal grossTotal = BigDecimal.ZERO;
        BigDecimal discountTotal = BigDecimal.ZERO;
        if (booking.getPassengers() != null) {
            for (BookingPassenger passenger : booking.getPassengers()) {
                QuotaType quota = passenger.getQuota() == null ? QuotaType.GENERAL : passenger.getQuota();
                int discount = clampDiscount(passenger.getQuotaDiscountPercent());
                BigDecimal lineDiscount = money(gross.multiply(BigDecimal.valueOf(discount))
                        .divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP));
                lines.add(FarePassengerLineDto.builder()
                        .fullName(passenger.getFullName())
                        .age(passenger.getAge())
                        .gender(passenger.getGender())
                        .quota(quota.name())
                        .quotaLabel(quota.getLabel())
                        .discountPercent(discount)
                        .grossFare(gross)
                        .discountAmount(lineDiscount)
                        .payableFare(money(gross.subtract(lineDiscount)))
                        .quotaNote(passenger.getQuotaNote())
                        .build());
                grossTotal = grossTotal.add(gross);
                discountTotal = discountTotal.add(lineDiscount);
            }
        }

        BigDecimal platformFee = money(booking.getPlatformFee());
        BigDecimal gstPercent = money(booking.getGstPercent());
        BigDecimal gstAmount = money(booking.getGstAmount());
        return FareBreakdownDto.builder()
                .currency(FareBreakdownDto.CURRENCY)
                .coachId(coach.getId())
                .coachCode(coach.getCoachCode())
                .seatClass(coach.getSeatClass().name())
                .seatClassLabel(coach.getSeatClass().getLabel())
                .distanceKm(booking.getDistanceKm() == null ? 0 : booking.getDistanceKm())
                .coachBaseFare(money(booking.getFareBase()))
                .farePerKm(money(booking.getFarePerKm()))
                .distanceFare(money(booking.getDistanceFare()))
                .minimumFare(money(booking.getFareMinimum()))
                .fareBasis(booking.getFareBasis())
                .grossFarePerPassenger(gross)
                .passengers(lines)
                .grossFareTotal(money(grossTotal))
                .discountTotal(money(discountTotal))
                .passengerFareTotal(money(booking.getPassengerFareTotal()))
                .platformFee(platformFee)
                .gstPercent(gstPercent)
                .gstAmount(gstAmount)
                .totalPayable(money(booking.getTotalFare()))
                .notes(List.of("Fare as stored when this booking was created. Simulated YatraRail "
                        + "tariff - not an official Indian Railways fare."))
                .build();
    }

    private static BigDecimal maxOf(BigDecimal a, BigDecimal b, BigDecimal c) {
        BigDecimal result = BigDecimal.ZERO;
        for (BigDecimal candidate : new BigDecimal[]{a, b, c}) {
            if (candidate != null && candidate.compareTo(result) > 0) {
                result = candidate;
            }
        }
        return money(result);
    }

    // ------------------------------------------------------------------ helpers

    /** Labels for which of the three pricing candidates set the fare. */
    public static final class FareBasis {
        public static final String COACH_BASE = "COACH_BASE";
        public static final String DISTANCE = "DISTANCE";
        public static final String MINIMUM = "MINIMUM";

        private FareBasis() {
        }
    }

    /** The four tariff numbers a breakdown is computed from, read once and never re-read mid-way. */
    public record Tariff(BigDecimal farePerKm, BigDecimal minimumFare, BigDecimal platformFee,
                         BigDecimal gstPercent, boolean configured) {
    }

    /**
     * One passenger's contribution: their identity for the itemised view, the quota they are really
     * booked under, and the concession that quota carries.
     */
    public record PassengerFare(String fullName, Integer age, String gender, QuotaType quota,
                                int discountPercent, String quotaNote) {
        public static PassengerFare of(String fullName, Integer age, String gender, QuotaType quota,
                                       int discountPercent, String quotaNote) {
            return new PassengerFare(fullName, age, gender,
                    quota == null ? QuotaType.GENERAL : quota, discountPercent, quotaNote);
        }
    }

    private static final BigDecimal ZERO = new BigDecimal("0.00");

    private static BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal nullSafe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static int clampDiscount(Integer value) {
        if (value == null) {
            return 0;
        }
        return Math.max(0, Math.min(90, value));
    }
}

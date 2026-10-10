package com.yatrarail.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Phase 5 — the cancellation refund policy, bound from {@code yatrarail.refund.*}.
 * <p>
 * The policy is a small ordered table rather than a chain of {@code if}s buried in a service, for
 * two reasons: the passenger is shown the <i>same</i> table the backend will apply, so the preview
 * can never disagree with the charge; and an operator can tighten or loosen a tier in deployment
 * without a code change.
 * <p>
 * The defaults deliberately reproduce the behaviour that shipped in Phases 2-4 exactly
 * (90% / 50% / 25%, platform fee returned in full when a whole booking is cancelled), so enabling
 * Phase 5 does not silently change what an existing cancellation is worth.
 */
@Validated
@ConfigurationProperties(prefix = "yatrarail.refund")
@Data
public class RefundProperties {

    /**
     * Ordered by {@code minDaysBeforeJourney} descending: the first tier whose threshold is met
     * wins, so {@code 7 -> 90%} means "7 or more days out".
     */
    @Valid
    @NotEmpty(message = "yatrarail.refund.tiers must contain at least one tier")
    private List<Tier> tiers = new ArrayList<>(List.of(
            new Tier(7, 90, "At least 7 days before departure"),
            new Tier(2, 50, "2 to 6 days before departure"),
            new Tier(0, 25, "Same day or 1 day before departure")));

    /**
     * Whether the flat platform fee is returned in full when every passenger of a booking is
     * cancelled. The fee is a booking-level convenience charge, not fare, so it is not subject to
     * the tiered percentage; on a <i>partial</i> cancellation it is never returned at all, because
     * the booking — and therefore the charge — still stands.
     */
    private boolean returnPlatformFeeOnFullCancellation = true;

    /** The tier that applies {@code daysBeforeJourney} days from now, or null if none qualifies. */
    public Tier resolve(long daysBeforeJourney) {
        return sorted().stream()
                .filter(tier -> daysBeforeJourney >= tier.getMinDaysBeforeJourney())
                .findFirst()
                .orElse(null);
    }

    /** The configured tiers, always ordered from the most generous to the least. */
    public List<Tier> sorted() {
        List<Tier> copy = new ArrayList<>(tiers);
        copy.sort(Comparator.comparingInt(Tier::getMinDaysBeforeJourney).reversed());
        return copy;
    }

    @Data
    public static class Tier {

        /** Lower bound, in days before the journey date, at which this tier starts to apply. */
        @NotNull
        @Min(value = 0, message = "a refund tier's min-days-before-journey cannot be negative")
        private Integer minDaysBeforeJourney;

        /** Percentage of the refundable fare returned by this tier, 0-100. */
        @NotNull
        @DecimalMin(value = "0.0", message = "a refund tier percentage cannot be negative")
        @DecimalMax(value = "100.0", message = "a refund tier percentage cannot exceed 100")
        private Integer refundPercent;

        /** Human-readable description, shown to the passenger in the refund preview. */
        @NotNull
        private String label;

        public Tier() {
        }

        public Tier(Integer minDaysBeforeJourney, Integer refundPercent, String label) {
            this.minDaysBeforeJourney = minDaysBeforeJourney;
            this.refundPercent = refundPercent;
            this.label = label;
        }
    }
}

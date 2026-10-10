package com.yatrarail.entity;

/**
 * The booking quotas YatraRail simulates.
 * <p>
 * A quota is two things at once: a <i>slice of a class's inventory</i> that only passengers of that
 * quota may consume, and (for most quotas) an <i>eligibility rule</i> the passenger must satisfy.
 * This is a simplified, academic model and does not reproduce the official Indian Railways quota
 * rules, which change per train and per class.
 * <p>
 * The slices of a class's physical capacity add up to the class capacity itself:
 * {@code general = total - (tatkal + senior + ladies + divyang + staff)}. General quota is therefore
 * never configured by an administrator - it is whatever is left over, which keeps the
 * configuration impossible to make inconsistent.
 */
public enum QuotaType {

    /** Open to everyone, and always the fallback when a reserved quota cannot serve a passenger. */
    GENERAL("General", "Open to every passenger", false, false),

    /**
     * Simulated emergency quota that only opens shortly before departure. The window is
     * configurable per train and class in days; outside it a Tatkal request is rejected outright
     * rather than silently ignored.
     */
    TATKAL("Tatkal (simulated)", "Emergency quota that opens just before departure", false, true),

    /** Concessionary quota for passengers aged 60 or above. */
    SENIOR("Senior Citizen", "Passengers aged 60 or above", true, false),

    /** Reserved quota for female passengers. */
    LADIES("Ladies", "Female passengers", true, false),

    /** Concessionary quota for passengers holding a disability certificate. */
    DIVYANG("Divyang", "Passengers holding a declared disability certificate", true, false),

    /** Reserved quota for YatraRail staff and their dependants. */
    STAFF("Staff Quota", "YatraRail staff and their dependants", true, false);

    /** Minimum age that qualifies a passenger for {@link #SENIOR}. */
    public static final int SENIOR_MIN_AGE = 60;

    private final String label;
    private final String description;
    private final boolean requiresEligibility;
    private final boolean timeGated;

    QuotaType(String label, String description, boolean requiresEligibility, boolean timeGated) {
        this.label = label;
        this.description = description;
        this.requiresEligibility = requiresEligibility;
        this.timeGated = timeGated;
    }

    public String getLabel() {
        return label;
    }

    public String getDescription() {
        return description;
    }

    /**
     * True when a passenger must prove they qualify before the quota can be used at all. Breaking
     * this is a hard error (400/403), never a silent downgrade.
     */
    public boolean isRequiresEligibility() {
        return requiresEligibility;
    }

    /** True when the quota only exists inside a departure window (currently only {@link #TATKAL}). */
    public boolean isTimeGated() {
        return timeGated;
    }

    /** True for every quota except {@link #GENERAL}: these carve a slice out of the class capacity. */
    public boolean isReserved() {
        return this != GENERAL;
    }
}

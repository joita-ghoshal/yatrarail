package com.yatrarail.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Tunables for the reservation engine. Every value has a safe local default and can be overridden
 * with an environment variable, so production deployments can tighten the payment window without a
 * rebuild.
 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "yatrarail.booking")
public class BookingProperties {

    /** How long a seat stays held for a PAYMENT_PENDING booking, in minutes. */
    @NotNull
    @Min(1)
    @Max(120)
    private Integer holdMinutes = 10;

    /** Maximum passengers in a single booking. */
    @NotNull
    @Min(1)
    @Max(10)
    private Integer maxPassengersPerBooking = 6;

    /** Cron expression for the job that releases expired seat holds. */
    @NotNull
    private String expiryCron = "0 * * * * *";

    /** Whether the pending-expiry job is enabled (disabled in tests that drive expiry manually). */
    private boolean expiryJobEnabled = true;
}

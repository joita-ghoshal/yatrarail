package com.yatrarail.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Phase 5 — configuration for the printable ticket and its QR code, bound from
 * {@code yatrarail.ticket.*}.
 * <p>
 * <b>Why a signature at all.</b> A ticket QR code is only worth scanning if the reader can tell a
 * genuine YatraRail ticket from a bitmap somebody printed themselves. A booking reference on its
 * own is guessable — eight characters from a 32-symbol alphabet is not much entropy to a script —
 * so the QR carries a reference <i>plus</i> an HMAC over it, computed with a secret that never
 * leaves the server. A forged code fails the signature check; a code that passes the check is
 * still only a claim, so verification additionally re-reads the live booking and reports its real
 * status rather than trusting anything printed on paper.
 * <p>
 * Binding validation runs at startup: a deployment missing the secret fails fast instead of
 * issuing tickets it cannot later verify.
 */
@Validated
@ConfigurationProperties(prefix = "yatrarail.ticket")
@Data
public class TicketProperties {

    /**
     * HMAC secret used to sign ticket QR payloads. Must be at least 32 characters. No default is
     * provided here — a profile or environment variable must supply it, exactly as with the JWT
     * secret, so a production deployment can never fall back to a value published in the source.
     */
    @NotBlank(message = "yatrarail.ticket.signing-secret must be configured")
    @Size(min = 32, message = "yatrarail.ticket.signing-secret must be at least 32 characters")
    private String signingSecret;

    /**
     * Identifies this deployment in the signed payload. A ticket signed by a staging secret will not
     * verify against a production secret even with an otherwise identical reference, which stops a
     * test ticket from being honoured by a real gate.
     */
    @NotBlank(message = "yatrarail.ticket.issuer must be configured")
    private String issuer = "yatrarail";

    /**
     * Payload version, carried inside the signed string so a future format change can still be
     * parsed (and rejected honestly) rather than being mistaken for a forgery.
     */
    @NotBlank(message = "yatrarail.ticket.payload-version must be configured")
    private String payloadVersion = "1";

    /** First segment of the QR payload, so a scanner is obviously pointed at a YatraRail ticket. */
    @NotBlank(message = "yatrarail.ticket.payload-prefix must be configured")
    private String payloadPrefix = "YATRARAIL-TICKET";

    /**
     * How long a generated ticket QR stays acceptable before the reader is told to look the booking
     * up fresh. Verification never <i>trusts</i> this window — the live booking is always the
     * authority — but a ticket printed a year ago is a poor thing to wave at a gate.
     */
    @NotNull
    @Min(value = 1, message = "yatrarail.ticket.valid-days must be at least 1")
    private Integer validDays = 365;
}

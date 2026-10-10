package com.yatrarail.dto.ticket;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Phase 5 — the body of a QR verification request: the scanned payload, verbatim.
 * <p>
 * The backend never interprets anything except this string, and it is validated only for shape. A
 * payload that is not a YatraRail ticket is rejected with a clear reason rather than being parsed
 * optimistically, so a scanner pointed at a random QR code produces an honest "unrecognised ticket"
 * rather than a confusing null-pointer failure.
 */
@Data
public class TicketVerifyRequest {

    @NotBlank(message = "Scan payload is required")
    @Size(max = 500, message = "Scan payload must be at most 500 characters")
    private String payload;
}

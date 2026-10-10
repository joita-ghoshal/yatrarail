package com.yatrarail.dto.ticket;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One row of the refund policy as it is printed on a ticket and shown in a refund preview.
 * <p>
 * The same rows are produced by {@code RefundService} for the preview and for the ticket, so the
 * terms a passenger reads before cancelling are literally the terms the backend will apply.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RefundPolicyRowDto {

    /** Human-readable band, e.g. "At least 7 days before departure". */
    private String label;

    /** Days before departure at which this band starts, inclusive. */
    private Integer fromDaysBeforeJourney;

    /** Percentage of the refundable fare returned in this band. */
    private Integer refundPercent;

    /** Plain-English statement of what the percentage applies to. */
    private String appliesTo;

    /** Whether the flat platform fee is also returned in this band. Only true on a full cancellation. */
    private Boolean platformFeeRefunded;
}

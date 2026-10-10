package com.yatrarail.dto.payment;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * One passenger's share of a refund, itemised so the arithmetic is visible.
 * <p>
 * GST is a separate line from the fare rather than being folded in, because it is levied on the
 * fare subtotal and not part of it — and because "90% of my fare, plus the tax I paid on it" is a
 * very different number to a passenger from "90% of what I paid", and they are entitled to see which
 * one they are getting.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PassengerRefundLineDto {

    private Long bookingPassengerId;
    private String fullName;
    private Integer age;
    private String quota;
    private String quotaLabel;

    /** Net fare charged for this passenger, as snapshotted at booking time. */
    private BigDecimal farePaid;

    /** GST charged on {@link #farePaid}, as snapshotted at booking time. */
    private BigDecimal gstPaid;

    /**
     * The part of {@link #farePaid} plus this passenger's GST that the tier does <i>not</i> return.
     */
    private BigDecimal cancellationCharge;

    /** Fare actually returned, after the tier percentage. */
    private BigDecimal fareRefunded;

    /** GST actually returned, after the tier percentage. */
    private BigDecimal gstRefunded;

    /** What this passenger gets back, in total. */
    private BigDecimal totalRefunded;
}

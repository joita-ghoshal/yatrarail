package com.yatrarail.dto.fare;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** One passenger's line of the fare breakdown: gross fare, the concession applied, and the result. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FarePassengerLineDto {

    private String fullName;
    private Integer age;
    private String gender;
    /** Quota the passenger is booked under - which may differ from the one requested. */
    private String quota;
    private String quotaLabel;
    /** Concession actually applied to this passenger, 0-90. */
    private int discountPercent;
    /** Gross fare for this passenger before any concession. */
    private BigDecimal grossFare;
    /** Concession amount, never negative. */
    private BigDecimal discountAmount;
    /** grossFare − discountAmount: what this passenger contributes to the passenger subtotal. */
    private BigDecimal payableFare;
    /** Set when the passenger's requested quota could not serve them; explains the substitution. */
    private String quotaNote;
}

package com.yatrarail.dto.fare;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * The full, itemised explanation of a price.
 * <p>
 * It answers three separate questions that a single fare number cannot:
 * <ol>
 *   <li><b>Why</b> is the per-passenger fare what it is — which of the three candidates (coach base
 *       fare, distance × fare-per-km, class minimum) actually set it;</li>
 *   <li><b>Who</b> gets a concession — one line per passenger with the quota and percentage applied;</li>
 *   <li><b>What else</b> is charged — the flat platform fee and the GST on the passenger subtotal.</li>
 * </ol>
 * Returned identically by the pre-booking quote and by the stored booking, so the number a passenger
 * reviews is exactly the number the server charges. Every figure is a simplified simulation and is
 * not an official Indian Railways tariff.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FareBreakdownDto {

    public static final String CURRENCY = "INR";

    private String currency;
    private Long coachId;
    private String coachCode;
    private String seatClass;
    private String seatClassLabel;
    /** Distance of the booked segment in km, not the train's full route length. */
    private int distanceKm;

    // ---- how the per-passenger gross fare was derived ----
    /** The coach's base fare for this class. */
    private BigDecimal coachBaseFare;
    /** The class's configured fare per km. */
    private BigDecimal farePerKm;
    /** farePerKm × distanceKm. */
    private BigDecimal distanceFare;
    /** The class's configured minimum fare. */
    private BigDecimal minimumFare;
    /** COACH_BASE, DISTANCE or MINIMUM - the candidate that set the gross fare. */
    private String fareBasis;
    /** max(coachBaseFare, distanceFare, minimumFare). */
    private BigDecimal grossFarePerPassenger;

    // ---- per-passenger concessions ----
    private List<FarePassengerLineDto> passengers;
    /** Σ grossFarePerPassenger over all passengers. */
    private BigDecimal grossFareTotal;
    /** Σ discountAmount over all passengers. */
    private BigDecimal discountTotal;
    /** grossFareTotal − discountTotal. */
    private BigDecimal passengerFareTotal;

    // ---- booking-level charges ----
    private BigDecimal platformFee;
    private BigDecimal gstPercent;
    private BigDecimal gstAmount;

    /** passengerFareTotal + platformFee + gstAmount: the amount the passenger is asked to pay. */
    private BigDecimal totalPayable;

    /** Standing disclaimers so the simulation is never mistaken for a real tariff. */
    private List<String> notes;
}

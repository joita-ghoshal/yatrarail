package com.yatrarail.dto.quota;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * How much of one quota's slice is left for one train, class and journey date. This is what the
 * passenger sees next to each quota option and what the allocation engine budgets against, so the
 * number displayed is always the number the engine used.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuotaAvailabilityDto {

    private String quota;
    private String label;
    private String description;
    /** Seats this quota may hand out on this journey date (0 for general when the class is full). */
    private int capacity;
    /** Live holds and confirmed reservations already charged to this quota. */
    private int used;
    /** capacity − used, never negative. */
    private int remaining;
    /** True for every quota except general: these are carved out of the class capacity. */
    private boolean reserved;
    /** False while a time-gated quota (Tatkal) is outside its window. */
    private boolean open;
    /** Human explanation when the quota cannot be used right now, otherwise null. */
    private String unavailableReason;
}

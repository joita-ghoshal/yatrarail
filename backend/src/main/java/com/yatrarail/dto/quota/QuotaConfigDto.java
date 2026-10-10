package com.yatrarail.dto.quota;

import com.yatrarail.entity.QuotaConfig;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * An administrator-facing view of one quota split. {@code generalSeats} and {@code totalCapacity}
 * are computed live from the train's current coaches rather than stored, so the editor can never
 * show a general quota that disagrees with the physical inventory.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuotaConfigDto {

    private Long id;
    private Long trainId;
    private String trainNumber;
    private String trainName;
    private String seatClass;
    private String seatClassLabel;
    /** Seats the train owns in this class right now (active coaches only). */
    private int totalCapacity;
    /** Whatever is left of the capacity after the reserved slices - never configured by hand. */
    private int generalSeats;
    private int tatkalSeats;
    private int seniorSeats;
    private int ladiesSeats;
    private int divyangSeats;
    private int staffSeats;
    private int tatkalWindowDays;
    private boolean active;

    public static QuotaConfigDto from(QuotaConfig config, int totalCapacity) {
        return QuotaConfigDto.builder()
                .id(config.getId())
                .trainId(config.getTrain().getId())
                .trainNumber(config.getTrain().getTrainNumber())
                .trainName(config.getTrain().getName())
                .seatClass(config.getSeatClass().name())
                .seatClassLabel(config.getSeatClass().getLabel())
                .totalCapacity(totalCapacity)
                .generalSeats(Math.max(0, totalCapacity - config.reservedSeats()))
                .tatkalSeats(zero(config.getTatkalSeats()))
                .seniorSeats(zero(config.getSeniorSeats()))
                .ladiesSeats(zero(config.getLadiesSeats()))
                .divyangSeats(zero(config.getDivyangSeats()))
                .staffSeats(zero(config.getStaffSeats()))
                .tatkalWindowDays(config.getTatkalWindowDays() == null ? 0 : config.getTatkalWindowDays())
                .active(!Boolean.FALSE.equals(config.getActive()))
                .build();
    }

    private static int zero(Integer value) {
        return value == null ? 0 : value;
    }
}

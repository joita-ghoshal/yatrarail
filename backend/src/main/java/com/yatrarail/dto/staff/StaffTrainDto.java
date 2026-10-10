package com.yatrarail.dto.staff;

import com.yatrarail.entity.Train;
import lombok.Builder;
import lombok.Data;

import java.time.LocalTime;

@Data
@Builder
public class StaffTrainDto {
    private Long id;
    private String trainNumber;
    private String name;
    private String type;
    private LocalTime departureTime;
    private LocalTime arrivalTime;
    private boolean active;

    public static StaffTrainDto from(Train train) {
        return StaffTrainDto.builder()
                .id(train.getId())
                .trainNumber(train.getTrainNumber())
                .name(train.getName())
                .type(train.getType() != null ? train.getType().name() : null)
                .departureTime(train.getDepartureTime())
                .arrivalTime(train.getArrivalTime())
                .active(Boolean.TRUE.equals(train.getActive()))
                .build();
    }
}
package com.yatrarail.dto.admin;

import com.yatrarail.entity.Train;
import lombok.Builder;
import lombok.Data;

import java.time.LocalTime;

@Data
@Builder
public class TrainDto {
    private Long id;
    private String trainNumber;
    private String name;
    private String type;
    private Long sourceStationId;
    private String sourceStationCode;
    private String sourceStationName;
    private Long destinationStationId;
    private String destinationStationCode;
    private String destinationStationName;
    private LocalTime departureTime;
    private LocalTime arrivalTime;
    private Integer durationMinutes;
    private String runsOn;
    private boolean active;

    public static TrainDto from(Train train) {
        return TrainDto.builder()
                .id(train.getId())
                .trainNumber(train.getTrainNumber())
                .name(train.getName())
                .type(train.getType() != null ? train.getType().name() : null)
                .sourceStationId(train.getSourceStation().getId())
                .sourceStationCode(train.getSourceStation().getCode())
                .sourceStationName(train.getSourceStation().getName())
                .destinationStationId(train.getDestinationStation().getId())
                .destinationStationCode(train.getDestinationStation().getCode())
                .destinationStationName(train.getDestinationStation().getName())
                .departureTime(train.getDepartureTime())
                .arrivalTime(train.getArrivalTime())
                .durationMinutes(train.getDurationMinutes())
                .runsOn(train.getRunsOn())
                .active(Boolean.TRUE.equals(train.getActive()))
                .build();
    }
}
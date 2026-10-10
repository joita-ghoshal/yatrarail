package com.yatrarail.dto.train;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrainDetailsDto {
    private Long trainId;
    private String trainNumber;
    private String trainName;
    private String trainType;
    private LocalTime departureTime;
    private LocalTime arrivalTime;
    private Integer durationMinutes;
    private String runsOn;
    /** Parsed day codes the train runs on, e.g. ["MON","TUE"]. */
    private List<String> runsOnDays;
    /** Distance the class fares below are calculated for. */
    private Integer distanceKm;
    private List<RouteStationDto> route;
    private List<ClassAvailability> classes;
}

package com.yatrarail.dto.train;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrainSearchResult {
    private Long trainId;
    private String trainNumber;
    private String trainName;
    private String trainType;
    private String sourceStationName;
    private String sourceStationCode;
    private Long sourceStationId;
    private String destinationStationName;
    private String destinationStationCode;
    private Long destinationStationId;
    /** Distance of the searched segment in km. */
    private Integer distanceKm;
    private LocalTime departureTime;
    private LocalTime arrivalTime;
    private Integer durationMinutes;
    private LocalDate journeyDate;
    private List<ClassAvailability> classAvailability;
}

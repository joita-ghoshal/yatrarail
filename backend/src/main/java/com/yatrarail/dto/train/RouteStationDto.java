package com.yatrarail.dto.train;

import com.yatrarail.entity.RouteStation;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RouteStationDto {
    private int sequenceNumber;
    private Long stationId;
    private String stationCode;
    private String stationName;
    private LocalTime arrivalTime;
    private LocalTime departureTime;
    private int haltMinutes;
    private int distanceFromSourceKm;

    public static RouteStationDto from(RouteStation rs) {
        return RouteStationDto.builder()
                .sequenceNumber(rs.getSequenceNumber())
                .stationId(rs.getStation().getId())
                .stationCode(rs.getStation().getCode())
                .stationName(rs.getStation().getName())
                .arrivalTime(rs.getArrivalTime())
                .departureTime(rs.getDepartureTime())
                .haltMinutes(rs.getHaltMinutes())
                .distanceFromSourceKm(rs.getDistanceFromSourceKm())
                .build();
    }
}

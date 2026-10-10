package com.yatrarail.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalTime;

@Entity
@Table(name = "route_stations", indexes = {
        @Index(name = "idx_route_train", columnList = "train_id"),
        @Index(name = "idx_route_train_seq", columnList = "train_id, sequence_number"),
        @Index(name = "idx_route_station", columnList = "station_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RouteStation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "train_id", nullable = false)
    private Train train;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "station_id", nullable = false)
    private Station station;

    @Column(nullable = false)
    private Integer sequenceNumber;

    private LocalTime arrivalTime;

    private LocalTime departureTime;

    @Builder.Default
    @Column(nullable = false)
    private Integer haltMinutes = 0;

    @Column(nullable = false)
    private Integer distanceFromSourceKm;
}

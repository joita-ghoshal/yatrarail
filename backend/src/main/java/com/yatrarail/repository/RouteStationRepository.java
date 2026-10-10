package com.yatrarail.repository;

import com.yatrarail.entity.RouteStation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RouteStationRepository extends JpaRepository<RouteStation, Long> {
    List<RouteStation> findByTrainIdOrderBySequenceNumberAsc(Long trainId);
    void deleteByTrainId(Long trainId);

    @Query("select rs from RouteStation rs where rs.train.id = :trainId and rs.station.id = :stationId")
    Optional<RouteStation> findByTrainIdAndStationId(@Param("trainId") Long trainId,
                                                     @Param("stationId") Long stationId);

    @Query("select max(rs.distanceFromSourceKm) from RouteStation rs where rs.train.id = :trainId")
    Integer findTotalDistanceKm(@Param("trainId") Long trainId);
}

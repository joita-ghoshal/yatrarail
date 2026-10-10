package com.yatrarail.repository;

import com.yatrarail.entity.Train;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface TrainRepository extends JpaRepository<Train, Long> {
    Optional<Train> findByTrainNumber(String trainNumber);
    boolean existsByTrainNumber(String trainNumber);
    long countByActiveTrue();

    @Query("select t from Train t where t.sourceStation.id = :sourceId and t.destinationStation.id = :destId and t.active = true")
    List<Train> findDirectTrains(Long sourceId, Long destId);

    /**
     * Every active train that calls at {@code sourceId} and then at {@code destId} later in its
     * route. Matching only on the train's origin/terminus would hide every mid-route segment, so
     * the sub-leg lookup has to go through the ordered stop list.
     */
    @Query("""
            select distinct rs.train from RouteStation rs
            where rs.station.id = :sourceId
              and rs.train.active = true
              and exists (
                  select 1 from RouteStation later
                  where later.train = rs.train
                    and later.station.id = :destId
                    and later.sequenceNumber > rs.sequenceNumber
              )
            """)
    List<Train> findTrainsServingSegment(Long sourceId, Long destId);

    List<Train> findByActiveTrue();
}

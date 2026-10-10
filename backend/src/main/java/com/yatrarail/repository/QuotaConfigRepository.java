package com.yatrarail.repository;

import com.yatrarail.entity.QuotaConfig;
import com.yatrarail.entity.SeatClass;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface QuotaConfigRepository extends JpaRepository<QuotaConfig, Long> {

    Optional<QuotaConfig> findByTrainIdAndSeatClass(Long trainId, SeatClass seatClass);

    boolean existsByTrainIdAndSeatClass(Long trainId, SeatClass seatClass);

    List<QuotaConfig> findByTrainIdOrderBySeatClassAsc(Long trainId);

    List<QuotaConfig> findAllByOrderByTrainIdAscSeatClassAsc();

    /**
     * Every (train, class) pair that has at least one active coach, i.e. every combination a quota
     * configuration can meaningfully exist for. Used by the startup backfill so a database created
     * before Phase 4 ends up fully configured without a manual step.
     */
    @Query("select distinct c.train.id, c.seatClass from Coach c where c.active = true order by c.train.id, c.seatClass")
    List<Object[]> findTrainClassPairsWithActiveCoaches();
}

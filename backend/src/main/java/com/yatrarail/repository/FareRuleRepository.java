package com.yatrarail.repository;

import com.yatrarail.entity.FareRule;
import com.yatrarail.entity.SeatClass;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface FareRuleRepository extends JpaRepository<FareRule, Long> {
    Optional<FareRule> findBySeatClass(SeatClass seatClass);
}

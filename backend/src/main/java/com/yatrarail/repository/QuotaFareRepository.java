package com.yatrarail.repository;

import com.yatrarail.entity.QuotaFare;
import com.yatrarail.entity.QuotaType;
import com.yatrarail.entity.SeatClass;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface QuotaFareRepository extends JpaRepository<QuotaFare, Long> {

    Optional<QuotaFare> findBySeatClassAndQuotaType(SeatClass seatClass, QuotaType quotaType);

    List<QuotaFare> findBySeatClass(SeatClass seatClass);

    List<QuotaFare> findAllByOrderBySeatClassAscQuotaTypeAsc();
}

package com.yatrarail.service;

import com.yatrarail.entity.Coach;
import com.yatrarail.entity.FareRule;
import com.yatrarail.entity.SeatClass;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FareCalculationServiceTest {

    /**
     * Phase 10: the calculator now reads fare rules through {@code FareRuleService} (which caches
     * them), so the test mocks that service rather than the repository directly.
     */
    @Mock
    private FareRuleService fareRuleService;

    private Coach coach(BigDecimal baseFare, SeatClass seatClass) {
        return Coach.builder().baseFare(baseFare).seatClass(seatClass).build();
    }

    @Test
    void fareIsMaxOfBaseFareDistanceFareAndMinimumFare() {
        FareRule rule = FareRule.builder()
                .seatClass(SeatClass.SLEEPER)
                .farePerKm(new BigDecimal("0.80"))
                .minimumFare(new BigDecimal("150"))
                .build();
        when(fareRuleService.find(SeatClass.SLEEPER)).thenReturn(Optional.of(rule));

        FareCalculationService service = new FareCalculationService(fareRuleService);

        // base fare 400, distance fare 0.8*1000=800 -> 800 wins
        BigDecimal fare = service.calculateFarePerPassenger(coach(new BigDecimal("400"), SeatClass.SLEEPER), 1000);
        assertThat(fare).isEqualByComparingTo("800.00");
    }

    @Test
    void minimumFareAppliesOverBaseFare() {
        FareRule rule = FareRule.builder()
                .seatClass(SeatClass.SECOND_AC)
                .farePerKm(new BigDecimal("3.00"))
                .minimumFare(new BigDecimal("800"))
                .build();
        when(fareRuleService.find(SeatClass.SECOND_AC)).thenReturn(Optional.of(rule));

        FareCalculationService service = new FareCalculationService(fareRuleService);

        // distance 100 -> 300, base 350 -> still below minimum 800
        BigDecimal fare = service.calculateFarePerPassenger(coach(new BigDecimal("350"), SeatClass.SECOND_AC), 100);
        assertThat(fare).isEqualByComparingTo("800.00");
    }

    @Test
    void refundTiersByDaysBeforeJourney() {
        FareCalculationService service = new FareCalculationService(fareRuleService);
        BigDecimal total = new BigDecimal("1000.00");

        assertThat(service.calculateRefund(total, 10)).isEqualByComparingTo("900.00");
        assertThat(service.calculateRefund(total, 5)).isEqualByComparingTo("500.00");
        assertThat(service.calculateRefund(total, 1)).isEqualByComparingTo("250.00");
    }
}
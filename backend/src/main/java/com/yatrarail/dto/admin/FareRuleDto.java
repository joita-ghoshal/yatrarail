package com.yatrarail.dto.admin;

import com.yatrarail.entity.FareRule;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** Admin view of one class tariff. DTO rather than the entity, like every other admin endpoint. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FareRuleDto {

    private Long id;
    private String seatClass;
    private String seatClassLabel;
    private BigDecimal farePerKm;
    private BigDecimal minimumFare;
    private BigDecimal platformFee;
    private BigDecimal gstPercent;

    public static FareRuleDto from(FareRule rule) {
        return FareRuleDto.builder()
                .id(rule.getId())
                .seatClass(rule.getSeatClass().name())
                .seatClassLabel(rule.getSeatClass().getLabel())
                .farePerKm(rule.getFarePerKm())
                .minimumFare(rule.getMinimumFare())
                .platformFee(rule.getPlatformFee())
                .gstPercent(rule.getGstPercent())
                .build();
    }
}

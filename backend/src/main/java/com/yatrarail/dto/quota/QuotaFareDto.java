package com.yatrarail.dto.quota;

import com.yatrarail.entity.QuotaFare;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuotaFareDto {

    private Long id;
    private String seatClass;
    private String seatClassLabel;
    private String quota;
    private String quotaLabel;
    private String quotaDescription;
    private int discountPercent;
    private String note;

    public static QuotaFareDto from(QuotaFare fare) {
        return QuotaFareDto.builder()
                .id(fare.getId())
                .seatClass(fare.getSeatClass().name())
                .seatClassLabel(fare.getSeatClass().getLabel())
                .quota(fare.getQuotaType().name())
                .quotaLabel(fare.getQuotaType().getLabel())
                .quotaDescription(fare.getQuotaType().getDescription())
                .discountPercent(fare.getDiscountPercent() == null ? 0 : fare.getDiscountPercent())
                .note(fare.getNote())
                .build();
    }
}

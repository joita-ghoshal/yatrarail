package com.yatrarail.dto.admin;

import com.yatrarail.entity.Coach;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class CoachDto {
    private Long id;
    private String coachCode;
    private String seatClass;
    private Integer totalSeats;
    private BigDecimal baseFare;
    /** RAC (Reservation Against Cancellation) slots this coach offers per journey date. */
    private Integer racCapacity;
    private boolean active;

    public static CoachDto from(Coach coach) {
        return CoachDto.builder()
                .id(coach.getId())
                .coachCode(coach.getCoachCode())
                .seatClass(coach.getSeatClass() != null ? coach.getSeatClass().name() : null)
                .totalSeats(coach.getTotalSeats())
                .baseFare(coach.getBaseFare())
                .racCapacity(coach.getRacCapacity())
                .active(Boolean.TRUE.equals(coach.getActive()))
                .build();
    }
}
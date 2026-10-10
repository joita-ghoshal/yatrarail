package com.yatrarail.dto.staff;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Request body for marking one passenger's gate check-in state. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BoardingRequest {
    /** NOT_BOARDED, BOARDED or NO_SHOW. */
    private String status;
}
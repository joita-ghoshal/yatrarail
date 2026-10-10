package com.yatrarail.dto.train;

import lombok.Data;

import java.time.LocalDate;

@Data
public class TrainSearchRequest {
    private String sourceCode;
    private String destinationCode;
    private LocalDate journeyDate;
    private String seatClass; // optional filter
}

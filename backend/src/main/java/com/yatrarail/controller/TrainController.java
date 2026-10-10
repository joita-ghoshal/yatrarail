package com.yatrarail.controller;

import com.yatrarail.dto.common.ApiResponse;
import com.yatrarail.dto.train.TrainDetailsDto;
import com.yatrarail.dto.train.TrainSearchRequest;
import com.yatrarail.dto.train.TrainSearchResult;
import com.yatrarail.service.TrainService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/trains")
@RequiredArgsConstructor
public class TrainController {

    private final TrainService trainService;

    @GetMapping("/search")
    public ResponseEntity<ApiResponse<List<TrainSearchResult>>> search(
            @RequestParam String sourceCode,
            @RequestParam String destinationCode,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate journeyDate,
            @RequestParam(required = false) String seatClass) {
        TrainSearchRequest request = new TrainSearchRequest();
        request.setSourceCode(sourceCode);
        request.setDestinationCode(destinationCode);
        request.setJourneyDate(journeyDate);
        request.setSeatClass(seatClass);
        return ResponseEntity.ok(ApiResponse.ok(trainService.search(request)));
    }

    /**
     * Full route plus live class availability.
     * <p>
     * Pass {@code sourceStationId} + {@code destinationStationId} to price the classes for that
     * segment; without them the fares cover the train's full route.
     */
    @GetMapping("/{trainId}/details")
    public ResponseEntity<ApiResponse<TrainDetailsDto>> getDetails(
            @PathVariable Long trainId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate journeyDate,
            @RequestParam(required = false) Long sourceStationId,
            @RequestParam(required = false) Long destinationStationId) {
        return ResponseEntity.ok(ApiResponse.ok(
                trainService.getDetails(trainId, journeyDate, sourceStationId, destinationStationId)));
    }
}

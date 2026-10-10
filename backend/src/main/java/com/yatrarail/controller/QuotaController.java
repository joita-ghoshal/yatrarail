package com.yatrarail.controller;

import com.yatrarail.dto.common.ApiResponse;
import com.yatrarail.dto.quota.QuotaAvailabilityDto;
import com.yatrarail.entity.Coach;
import com.yatrarail.exception.ResourceNotFoundException;
import com.yatrarail.repository.CoachRepository;
import com.yatrarail.service.QuotaService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * Read-only quota visibility for the booking flow.
 * <p>
 * This endpoint deliberately exposes <i>availability</i> and not <i>eligibility decisions</i>: it
 * tells a passenger how many seats each quota has left and, when a quota cannot be used, why. It
 * never says whether a particular passenger may claim a quota, because that check depends on who is
 * asking and stays in {@link QuotaService#validateEligibility} on the booking path. A client that
 * hides a quota it thinks the passenger cannot use is a courtesy; a client that is trusted to
 * enforce it is a vulnerability.
 */
@RestController
@RequestMapping("/api/quotas")
@RequiredArgsConstructor
public class QuotaController {

    private final QuotaService quotaService;
    private final CoachRepository coachRepository;

    @GetMapping("/availability/{coachId}")
    public ResponseEntity<ApiResponse<List<QuotaAvailabilityDto>>> availability(
            @PathVariable Long coachId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate journeyDate) {
        Coach coach = coachRepository.findById(coachId)
                .orElseThrow(() -> new ResourceNotFoundException("Coach not found"));
        return ResponseEntity.ok(ApiResponse.ok(quotaService.availability(coach, journeyDate)));
    }
}

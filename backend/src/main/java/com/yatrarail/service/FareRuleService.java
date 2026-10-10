package com.yatrarail.service;

import com.yatrarail.config.CacheConfig.CacheNames;
import com.yatrarail.entity.FareRule;
import com.yatrarail.entity.SeatClass;
import com.yatrarail.repository.FareRuleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Phase 10 — the one place fare rules are read.
 * <p>
 * Fare rules are the hottest master-data read in the system: pricing resolves a class's tariff for
 * every search result, every fare preview and every passenger on every booking, while the rules
 * themselves change perhaps a few times a year. That ratio is exactly what a cache is for, and it
 * is the per-class entry (not the admin list) that is worth caching, because the list endpoint is
 * an administrator action and the per-class lookup is on the booking hot path.
 * <p>
 * Extracting this from {@code FareCalculationService} avoids a cycle: {@code QuotaAdminService}
 * already depends on the fare calculator, so the calculator could not depend back on it. A tiny,
 * dedicated reader keeps the dependency graph acyclic and gives the cache a single owner.
 * <p>
 * Eviction is by cache name, so {@code QuotaAdminService} clears {@link CacheNames#FARE_RULES} when
 * it edits a rule even though this class is what populated it.
 */
@Service
@RequiredArgsConstructor
public class FareRuleService {

    private final FareRuleRepository fareRuleRepository;

    /**
     * The tariff for a class, or empty when none is configured. Cached by class.
     * <p>
     * An {@code Optional} is cached rather than only positive results, because "this class has no
     * rule" is also stable master data and re-querying it on every passenger is the same waste.
     */
    @Cacheable(cacheNames = CacheNames.FARE_RULES, key = "#seatClass")
    @Transactional(readOnly = true)
    public Optional<FareRule> find(SeatClass seatClass) {
        return fareRuleRepository.findBySeatClass(seatClass);
    }
}

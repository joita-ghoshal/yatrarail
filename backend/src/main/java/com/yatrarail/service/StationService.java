package com.yatrarail.service;

import com.yatrarail.config.CacheConfig.CacheNames;
import com.yatrarail.dto.admin.StationRequest;
import com.yatrarail.entity.Station;
import com.yatrarail.exception.ConflictException;
import com.yatrarail.exception.ResourceNotFoundException;
import com.yatrarail.repository.StationRepository;
import com.yatrarail.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Station master data.
 * <p>
 * Every mutation takes the acting admin's id and writes an audit row, so "who added this station"
 * is answerable years later. The reads stay un-audited: logging a page view would bury the changes.
 */
@Service
@RequiredArgsConstructor
public class StationService {

    private final StationRepository stationRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;

    /**
     * Phase 10 — the station list is the most-read, least-changed data in the platform: every
     * search box, route editor and admin form pulls it, and an administrator adds a station a few
     * times a year. It is therefore the textbook cache entry.
     * <p>
     * It is safe to cache precisely because it is master data with no request-scoped state. Live
     * inventory is deliberately absent from every cache in this application.
     */
    @Cacheable(cacheNames = CacheNames.STATION_LIST)
    @Transactional(readOnly = true)
    public List<Station> getAll() {
        return stationRepository.findAll();
    }

    @CacheEvict(cacheNames = CacheNames.STATION_LIST, allEntries = true)
    @Transactional
    public Station create(StationRequest request) {
        return create(request, null);
    }

    @CacheEvict(cacheNames = CacheNames.STATION_LIST, allEntries = true)
    @Transactional
    public Station create(StationRequest request, Long actorId) {
        if (stationRepository.existsByCode(request.getCode().toUpperCase())) {
            throw new ConflictException("A station with this code already exists");
        }
        Station station = Station.builder()
                .code(request.getCode().toUpperCase())
                .name(request.getName())
                .city(request.getCity())
                .state(request.getState())
                .active(true)
                .build();
        Station saved = stationRepository.save(station);
        auditService.record(AuditService.STATION_CREATED, "STATION", saved.getId(),
                actor(actorId) + " created station " + saved.getCode() + " (" + saved.getName() + ")", actorId);
        return saved;
    }

    @CacheEvict(cacheNames = CacheNames.STATION_LIST, allEntries = true)
    @Transactional
    public Station update(Long id, StationRequest request) {
        return update(id, request, null);
    }

    @CacheEvict(cacheNames = CacheNames.STATION_LIST, allEntries = true)
    @Transactional
    public Station update(Long id, StationRequest request, Long actorId) {
        Station station = stationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Station not found"));
        station.setName(request.getName());
        station.setCity(request.getCity());
        station.setState(request.getState());
        Station saved = stationRepository.save(station);
        auditService.record(AuditService.STATION_UPDATED, "STATION", saved.getId(),
                actor(actorId) + " updated station " + saved.getCode(), actorId);
        return saved;
    }

    @CacheEvict(cacheNames = CacheNames.STATION_LIST, allEntries = true)
    @Transactional
    public Station setActive(Long id, boolean active) {
        return setActive(id, active, null);
    }

    @CacheEvict(cacheNames = CacheNames.STATION_LIST, allEntries = true)
    @Transactional
    public Station setActive(Long id, boolean active, Long actorId) {
        Station station = stationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Station not found"));
        if (active == Boolean.TRUE.equals(station.getActive())) {
            return station;
        }
        station.setActive(active);
        Station saved = stationRepository.save(station);
        auditService.record(active ? AuditService.STATION_ACTIVATED : AuditService.STATION_DEACTIVATED,
                "STATION", saved.getId(),
                actor(actorId) + (active ? " re-activated " : " deactivated ") + "station " + saved.getCode(),
                actorId);
        return saved;
    }

    private String actor(Long actorId) {
        if (actorId == null) {
            return "System";
        }
        return userRepository.findById(actorId)
                .map(u -> u.getFullName() + " (" + u.getRole().name() + ")")
                .orElse("System");
    }
}

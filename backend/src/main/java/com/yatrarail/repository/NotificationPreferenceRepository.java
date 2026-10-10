package com.yatrarail.repository;

import com.yatrarail.entity.NotificationPreference;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface NotificationPreferenceRepository extends JpaRepository<NotificationPreference, Long> {

    /**
     * Absent means "never configured", which is not the same as "configured to the defaults" — see
     * {@link com.yatrarail.service.NotificationPreferenceService}, which treats the missing row as
     * the defaults rather than writing one on every read.
     */
    Optional<NotificationPreference> findByUserId(Long userId);
}
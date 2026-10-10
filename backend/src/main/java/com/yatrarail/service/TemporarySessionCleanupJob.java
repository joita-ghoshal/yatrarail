package com.yatrarail.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Housekeeping for guest one-time accounts.
 * <p>
 * Expiry is already enforced on every request, so this job is belt-and-braces rather than the
 * mechanism of record: it deactivates stale sessions so a leaked token is also refused if it were
 * ever replayed through a path that skips the request filter, and it purges expired guests that own
 * no bookings. An account with bookings is never deleted, so ticket history is never orphaned.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "yatrarail.temporary-session", name = "cleanup-job-enabled",
        havingValue = "true", matchIfMissing = true)
public class TemporarySessionCleanupJob {

    private final TemporaryAccountService temporaryAccountService;

    @Scheduled(cron = "${yatrarail.temporary-session.cleanup-cron:0 */15 * * * *}")
    public void cleanUp() {
        try {
            temporaryAccountService.expireStaleSessions();
        } catch (RuntimeException ex) {
            log.warn("Temporary session cleanup failed: {}", ex.getMessage());
        }
    }
}

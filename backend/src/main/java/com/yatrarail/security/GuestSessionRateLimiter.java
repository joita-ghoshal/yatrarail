package com.yatrarail.security;

import com.yatrarail.config.TemporarySessionProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * IP-keyed sliding-window throttle for opening guest sessions.
 * <p>
 * Unlike {@link LoginAttemptService}, which only counts failures, this counts <em>successes</em>:
 * every call inserts a user row, so the cap is what stops an anonymous client from minting unlimited
 * accounts. In-memory by design, matching the existing brute-force layer - it is a cheap guard, not
 * a quota system. State resets on restart, which only ever makes the limit slightly more generous.
 */
@Service
@RequiredArgsConstructor
public class GuestSessionRateLimiter {

    private final TemporarySessionProperties properties;

    private final Map<String, Deque<Instant>> creations = new ConcurrentHashMap<>();

    /** True (and enforces 429) when this IP has already opened the maximum number of sessions. */
    public boolean isBlocked(String ip) {
        Deque<Instant> deque = creations.get(ip);
        if (deque == null || deque.isEmpty()) {
            return false;
        }
        prune(deque);
        return deque.size() >= properties.getMaxSessionsPerIp();
    }

    public void record(String ip) {
        Deque<Instant> deque = creations.computeIfAbsent(ip, k -> new ArrayDeque<>());
        deque.addLast(Instant.now());
        prune(deque);
        // Keep the map from growing without bound from one-off client addresses.
        while (deque.size() > properties.getMaxSessionsPerIp() * 2L) {
            deque.removeFirst();
        }
    }

    public int remaining(String ip) {
        Deque<Instant> deque = creations.get(ip);
        if (deque == null) {
            return properties.getMaxSessionsPerIp();
        }
        prune(deque);
        return Math.max(0, properties.getMaxSessionsPerIp() - deque.size());
    }

    private void prune(Deque<Instant> deque) {
        Instant cutoff = Instant.now().minus(Duration.ofMinutes(properties.getRateWindowMinutes()));
        while (!deque.isEmpty() && deque.peekFirst().isBefore(cutoff)) {
            deque.removeFirst();
        }
    }
}

package com.yatrarail.security;

import com.yatrarail.config.SecurityProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory, contention-free failed-login guard keyed by IP|lower(email).
 * Successes clear the key; expired entries are pruned lazily on each check and
 * when a key is evicted after repeated failures. State is lost on restart, which
 * is acceptable for this layer; intent is to throttle brute-force attempts.
 */
@Service
@RequiredArgsConstructor
public class LoginAttemptService {

    private final SecurityProperties securityProperties;

    private final Map<String, Deque<Instant>> failedAttempts = new ConcurrentHashMap<>();

    private String key(String ip, String email) {
        return ip + "|" + (email == null ? "" : email.toLowerCase());
    }

    /** Returns true (and enforces 429) when attempts exceed the allowed maximum within the window. */
    public boolean isBlocked(String ip, String email) {
        Deque<Instant> deque = failedAttempts.get(key(ip, email));
        if (deque == null || deque.isEmpty()) {
            return false;
        }
        pruneIfNecessary(deque);
        return deque.size() >= securityProperties.getLogin().getMaxAttempts();
    }

    public int remainingAttempts(String ip, String email) {
        Deque<Instant> deque = failedAttempts.get(key(ip, email));
        if (deque == null) {
            return securityProperties.getLogin().getMaxAttempts();
        }
        pruneIfNecessary(deque);
        return Math.max(0, securityProperties.getLogin().getMaxAttempts() - deque.size());
    }

    public void recordFailure(String ip, String email) {
        Deque<Instant> deque = failedAttempts.computeIfAbsent(key(ip, email), k -> new ArrayDeque<>());
        deque.addLast(Instant.now());
        pruneIfNecessary(deque);
        if (deque.size() > securityProperties.getLogin().getMaxAttempts() * 2L) {
            while (deque.size() > securityProperties.getLogin().getMaxAttempts()) {
                deque.removeFirst();
            }
        }
    }

    public void reset(String ip, String email) {
        failedAttempts.remove(key(ip, email));
    }

    private void pruneIfNecessary(Deque<Instant> deque) {
        Instant cutoff = Instant.now().minus(Duration.ofMinutes(securityProperties.getLogin().getWindowMinutes()));
        while (!deque.isEmpty() && deque.peekFirst().isBefore(cutoff)) {
            deque.removeFirst();
        }
    }
}
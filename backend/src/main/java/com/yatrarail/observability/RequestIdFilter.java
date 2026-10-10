package com.yatrarail.observability;

import com.yatrarail.config.PlatformProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Phase 10 — correlates every log line with the request that caused it.
 * <p>
 * A request id is created (or, if the caller already sent a sane {@code X-Request-Id}, adopted) and
 * put in the logging MDC, where the console pattern renders it on every line. The same id is echoed
 * back in the response header, so a user reporting a failure can quote one string that finds every
 * log line across every thread and service the request touched.
 * <p>
 * Runs at the very front of the chain, before authentication and before business logic, so even a
 * rejected request is traceable. The id is not used for correctness, throttling or security — it is
 * purely for correlation, which is why an inbound value is accepted rather than trusted.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    private static final Logger log = LoggerFactory.getLogger(RequestIdFilter.class);
    private static final int MAX_INBOUND_LENGTH = 64;

    private final PlatformProperties properties;

    public RequestIdFilter(PlatformProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String requestId = inboundId(request);
        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER, requestId);

        long start = System.nanoTime();
        try {
            filterChain.doFilter(request, response);
        } finally {
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            if (elapsedMs >= properties.getSlowRequestMs()) {
                log.warn("slow request {} {} status={} took={}ms",
                        request.getMethod(), request.getRequestURI(),
                        response.getStatus(), elapsedMs);
            }
            // Never leak the id into the next request on a pooled thread.
            MDC.remove(MDC_KEY);
        }
    }

    /**
     * Adopts a caller-supplied id only when it is short and looks like an id (hex/dash), so a header
     * cannot be used to smuggle newlines into the log or bloat every line.
     */
    private String inboundId(HttpServletRequest request) {
        String provided = request.getHeader(HEADER);
        if (provided != null && !provided.isBlank() && provided.length() <= MAX_INBOUND_LENGTH
                && provided.matches("[A-Za-z0-9._-]+")) {
            return provided;
        }
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}

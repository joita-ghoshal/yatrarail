package com.yatrarail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yatrarail.config.CacheConfig.CacheNames;
import com.yatrarail.dto.admin.StationRequest;
import com.yatrarail.entity.RoleName;
import com.yatrarail.entity.Station;
import com.yatrarail.entity.User;
import com.yatrarail.observability.DomainEventPublisher;
import com.yatrarail.observability.PlatformEvent;
import com.yatrarail.repository.StationRepository;
import com.yatrarail.repository.UserRepository;
import com.yatrarail.service.StationService;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.interceptor.SimpleKey;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 10 — the production-readiness surface: health, metrics, caching, event dispatch and the
 * paged admin ledgers.
 * <p>
 * The theme is that a system which is expected to stay up must be <i>observable</i> and must degrade
 * in bounded ways. Each test here pins one of those properties: a public liveness check that reports
 * domain state, an operational endpoint that is not public, caches that are populated and evicted at
 * the right moments, a domain event that becomes a metric, and a ledger endpoint that answers with a
 * page rather than the whole table.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProductionReadinessIntegrationTest {

    private static final AtomicInteger COUNTER = new AtomicInteger();
    private static final String ADMIN_EMAIL = "admin@yatrarail.demo";
    private static final String ADMIN_PASSWORD = "Admin@123";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private CacheManager cacheManager;
    @Autowired
    private MeterRegistry meterRegistry;
    @Autowired
    private DomainEventPublisher domainEventPublisher;
    @Autowired
    private StationService stationService;
    @Autowired
    private StationRepository stationRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private TransactionTemplate transactionTemplate;

    private Long passengerId;
    private Long createdStationId;

    @BeforeEach
    void setUp() {
        passengerId = newUser("p10-passenger-" + COUNTER.incrementAndGet() + "@example.com");
    }

    @AfterEach
    void cleanUp() {
        if (createdStationId != null) {
            transactionTemplate.executeWithoutResult(status -> stationRepository.deleteById(createdStationId));
            createdStationId = null;
        }
    }

    // ==================================================================
    // Health and observability
    // ==================================================================

    @Nested
    @DisplayName("the health and observability surface")
    class Observability {

        @Test
        @DisplayName("the health endpoint is public and reports the domain components")
        void healthIsPublicAndReportsDomainComponents() throws Exception {
            // No Authorization header: a load balancer must be able to poll this.
            mockMvc.perform(get("/actuator/health"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("UP"))
                    // The database ping proves connectivity...
                    .andExpect(jsonPath("$.components.db").exists())
                    // ...and these two prove something domain-specific: inventory is readable and
                    // the async queue has room.
                    .andExpect(jsonPath("$.components.bookingEngine.status").value("UP"))
                    .andExpect(jsonPath("$.components.platformQueue.status").value("UP"));
        }

        @Test
        @DisplayName("metrics require an administrator, health does not")
        void metricsRequireAdmin() throws Exception {
            mockMvc.perform(get("/actuator/metrics"))
                    .andExpect(status().is4xxClientError());

            mockMvc.perform(get("/actuator/metrics")
                            .header("Authorization", "Bearer " + tokenForPassenger()))
                    .andExpect(status().isForbidden());

            mockMvc.perform(get("/actuator/metrics")
                            .header("Authorization", "Bearer " + adminToken()))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("a published domain event becomes a metric")
        void domainEventsBecomeMetrics() {
            domainEventPublisher.publish(PlatformEvent.of("P10_TEST_EVENT", "readiness"));

            // The synchronous metrics listener runs on the publishing thread, so the counter is
            // already durable by the time publish() returns.
            assertThat(meterRegistry.counter("yatrarail.domain.events", "type", "P10_TEST_EVENT").count())
                    .isEqualTo(1.0);
        }

        @Test
        @DisplayName("every response carries a request id for log correlation")
        void everyResponseCarriesARequestId() throws Exception {
            mockMvc.perform(get("/api/stations"))
                    .andExpect(status().isOk())
                    .andExpect(header().exists("X-Request-Id"));
        }

        @Test
        @DisplayName("a caller-supplied request id is adopted when it is well formed")
        void inboundRequestIdIsAdopted() throws Exception {
            mockMvc.perform(get("/api/stations").header("X-Request-Id", "trace-abc-123"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("X-Request-Id", "trace-abc-123"));
        }
    }

    // ==================================================================
    // Caching
    // ==================================================================

    @Nested
    @DisplayName("caching of master data")
    class Caching {

        @Test
        @DisplayName("the station list is cached, and a station edit evicts it")
        void stationListIsCachedAndEvictedOnChange() {
            Cache cache = cacheManager.getCache(CacheNames.STATION_LIST);
            assertThat(cache).isNotNull();

            stationService.getAll();
            assertThat(cache.get(SimpleKey.EMPTY)).as("station list cached after a read").isNotNull();

            createdStationId = stationService.create(stationRequest()).getId();

            assertThat(cache.get(SimpleKey.EMPTY)).as("cache evicted by the station write").isNull();
        }
    }

    // ==================================================================
    // Paged admin ledgers
    // ==================================================================

    @Nested
    @DisplayName("the paged admin ledgers")
    class PagedLedgers {

        @Test
        @DisplayName("payments answer with a page envelope, not a bare array")
        void paymentsAnswerWithAPageEnvelope() throws Exception {
            MvcResult result = mockMvc.perform(get("/api/admin/payments?page=0&size=5")
                            .header("Authorization", "Bearer " + adminToken()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content").isArray())
                    .andExpect(jsonPath("$.data.page").value(0))
                    .andExpect(jsonPath("$.data.size").value(5))
                    .andExpect(jsonPath("$.data.totalElements").exists())
                    .andExpect(jsonPath("$.data.last").exists())
                    .andReturn();
            // Sanity: the envelope is a page, not the whole ledger.
            int returned = objectMapper.readTree(result.getResponse().getContentAsString())
                    .path("data").path("content").size();
            assertThat(returned).isLessThanOrEqualTo(5);
        }

        @Test
        @DisplayName("an oversized page is clamped, so one caller cannot ask for the whole table")
        void oversizedPageIsClamped() throws Exception {
            mockMvc.perform(get("/api/admin/payments?page=0&size=1000000")
                            .header("Authorization", "Bearer " + adminToken()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.size").value(100));
        }

        @Test
        @DisplayName("refunds are paged too, and the search filter is accepted")
        void refundsArePagedAndSearchable() throws Exception {
            mockMvc.perform(get("/api/admin/refunds?page=0&size=10&search=nothingmatches")
                            .header("Authorization", "Bearer " + adminToken()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content").isArray())
                    .andExpect(jsonPath("$.data.page").value(0));
        }

        @Test
        @DisplayName("a passenger cannot read the ledgers")
        void passengerCannotReadLedgers() throws Exception {
            mockMvc.perform(get("/api/admin/payments")
                            .header("Authorization", "Bearer " + tokenForPassenger()))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get("/api/admin/refunds")
                            .header("Authorization", "Bearer " + tokenForPassenger()))
                    .andExpect(status().isForbidden());
        }
    }

    // ==================================================================
    // Helpers
    // ==================================================================

    private StationRequest stationRequest() {
        StationRequest request = new StationRequest();
        request.setCode("P10" + (char) ('A' + COUNTER.incrementAndGet() % 26));
        request.setName("Phase 10 Test Station");
        request.setCity("Test City");
        request.setState("Test State");
        return request;
    }

    private String adminToken() throws Exception {
        return login(ADMIN_EMAIL, ADMIN_PASSWORD);
    }

    private String tokenForPassenger() throws Exception {
        User user = userRepository.findById(passengerId).orElseThrow();
        return login(user.getEmail(), "Password@123");
    }

    private Long newUser(String email) {
        return transactionTemplate.execute(status -> userRepository.saveAndFlush(User.builder()
                .fullName("P10 Passenger")
                .email(email)
                .phone("97" + String.format("%08d", COUNTER.incrementAndGet()))
                .password(passwordEncoder.encode("Password@123"))
                .role(RoleName.PASSENGER)
                .active(true)
                .temporaryAccount(false)
                .build()).getId());
    }

    private String login(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", email, "password", password))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("token").asText();
    }
}

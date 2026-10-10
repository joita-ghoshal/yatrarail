package com.yatrarail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yatrarail.entity.*;
import com.yatrarail.repository.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 7 — the admin console: an accountability layer over the existing system.
 * <p>
 * The theme is that administration is a privilege and must leave a trace. Most of what is asserted
 * here is a boundary rather than a feature: a passenger cannot read the audit trail, an admin cannot
 * quietly remove the last admin, the audit trail itself is not writable, and a seat class cannot be
 * changed under an already-allocated seat. The happy paths are deliberately few and short.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminOperationsIntegrationTest {

    private static final AtomicInteger COUNTER = new AtomicInteger();
    private static final AtomicInteger PHONE_COUNTER = new AtomicInteger(5_000);
    private static final String ADMIN_EMAIL = "admin@yatrarail.demo";
    private static final String ADMIN_PASSWORD = "Admin@123";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private StationRepository stationRepository;
    @Autowired
    private TrainRepository trainRepository;
    @Autowired
    private RouteStationRepository routeStationRepository;
    @Autowired
    private CoachRepository coachRepository;
    @Autowired
    private SeatRepository seatRepository;
    @Autowired
    private AuditLogRepository auditLogRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private TransactionTemplate transactionTemplate;

    private Long adminId;
    private Long passengerId;
    private Long staffId;
    private Long ndls;
    private Long lko;
    /**
     * Trains created by a test, removed afterwards. The H2 database is shared across test classes in
     * the same cached context, so a test that adds trains must not leave them behind for a later
     * class that counts them.
     */
    private final List<Long> createdTrainIds = new java.util.ArrayList<>();

    @BeforeEach
    void setUp() {
        int n = COUNTER.incrementAndGet();
        adminId = userRepository.findByEmail(ADMIN_EMAIL).orElseThrow().getId();
        passengerId = newUser("p7t-passenger-" + n + "@example.com", "P7 Passenger", RoleName.PASSENGER);
        staffId = newUser("p7t-staff-" + n + "@example.com", "P7 Staff", RoleName.STAFF);
        ndls = stationId("NDLS");
        lko = stationId("LKO");
    }

    @AfterEach
    void removeCreatedTrains() {
        if (createdTrainIds.isEmpty()) {
            return;
        }
        transactionTemplate.executeWithoutResult(status -> {
            for (Long trainId : createdTrainIds) {
                for (Coach coach : coachRepository.findByTrainId(trainId)) {
                    seatRepository.deleteByCoachId(coach.getId());
                }
                coachRepository.deleteByTrainId(trainId);
                routeStationRepository.deleteByTrainId(trainId);
                trainRepository.deleteById(trainId);
            }
            coachRepository.flush();
            trainRepository.flush();
        });
        createdTrainIds.clear();
    }

    // ==================================================================
    // Who may administer at all
    // ==================================================================

    @Nested
    @DisplayName("the admin surface is for admins")
    class Authority {

        @Test
        @DisplayName("a passenger cannot read the audit trail")
        void aPassengerCannotReadTheAuditTrail() throws Exception {
            mockMvc.perform(get("/api/admin/audit-logs")
                            .header("Authorization", "Bearer " + tokenForUser(passengerId)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("staff cannot read the audit trail either")
        void staffCannotReadTheAuditTrail() throws Exception {
            mockMvc.perform(get("/api/admin/audit-logs")
                            .header("Authorization", "Bearer " + tokenForUser(staffId)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("an anonymous caller is rejected")
        void anAnonymousCallerIsRejected() throws Exception {
            mockMvc.perform(get("/api/admin/users"))
                    .andExpect(status().is4xxClientError());
        }

        @Test
        @DisplayName("a passenger cannot create an account")
        void aPassengerCannotCreateAnAccount() throws Exception {
            String body = objectMapper.writeValueAsString(Map.of(
                    "fullName", "Sneaky", "email", "p7t-sneaky-" + COUNTER.incrementAndGet() + "@example.com",
                    "phone", "9800000000", "role", "ADMIN", "password", "Password@123"));
            mockMvc.perform(post("/api/admin/users")
                            .header("Authorization", "Bearer " + tokenForUser(passengerId))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isForbidden());
        }
    }

    // ==================================================================
    // The audit trail
    // ==================================================================

    @Nested
    @DisplayName("the audit trail")
    class AuditTrail {

        @Test
        @DisplayName("records who created a station, and against which row")
        void creatingAStationIsRecorded() throws Exception {
            String code = stationCode();
            long stationId = createStation(code);

            JsonNode page = auditPage("action=STATION_CREATED&search=" + code.toLowerCase());
            assertThat(page.path("totalElements").asLong()).isEqualTo(1);
            JsonNode row = page.path("content").get(0);
            assertThat(row.path("entityType").asText()).isEqualTo("STATION");
            assertThat(row.path("entityId").asLong()).isEqualTo(stationId);
            assertThat(row.path("actorRole").asText()).isEqualTo("ADMIN");
            // The summary names the actor, so the trail answers "who" without a join.
            assertThat(row.path("summary").asText()).contains("Admin User").contains(code);
        }

        @Test
        @DisplayName("a deactivate and a re-activate are two distinct actions")
        void activateAndDeactivateAreDistinct() throws Exception {
            long stationId = createStation(stationCode());
            setStationActive(stationId, false);
            setStationActive(stationId, true);

            JsonNode page = auditPage("entityType=STATION&entityId=" + stationId);
            List<String> actions = new java.util.ArrayList<>();
            page.path("content").forEach(r -> actions.add(r.path("action").asText()));
            assertThat(actions).contains("STATION_DEACTIVATED", "STATION_ACTIVATED");
        }

        @Test
        @DisplayName("a no-op re-activation writes no row")
        void aNoOpWritesNothing() throws Exception {
            long stationId = createStation(stationCode());
            // The station is created active; asking for active again changes nothing and must not
            // fill the trail with noise.
            setStationActive(stationId, true);

            JsonNode page = auditPage("entityType=STATION&entityId=" + stationId);
            List<String> actions = new java.util.ArrayList<>();
            page.path("content").forEach(r -> actions.add(r.path("action").asText()));
            assertThat(actions).containsExactly("STATION_CREATED");
        }

        @Test
        @DisplayName("the newest row is first")
        void newestFirst() throws Exception {
            String firstCode = stationCode();
            createStation(firstCode);
            String secondCode = stationCode();
            createStation(secondCode);

            JsonNode content = auditPage("action=STATION_CREATED&size=100").path("content");
            assertThat(indexOfSummaryContaining(content, secondCode))
                    .isLessThan(indexOfSummaryContaining(content, firstCode));
        }

        @Test
        @DisplayName("the action list is drawn from the data, not hard-coded")
        void knownActionsComeFromTheData() throws Exception {
            createStation(stationCode());

            mockMvc.perform(get("/api/admin/audit-logs/actions")
                            .header("Authorization", "Bearer " + adminToken()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[?(@=='STATION_CREATED')]").isNotEmpty());
        }

        @Test
        @DisplayName("the trail cannot be written to through the API")
        void theTrailIsNotWritable() throws Exception {
            // The audit endpoint exposes GET only. Attempting any write method is a 405, which is the
            // API-level expression of "append-only": there is no route that lets a caller forge a row.
            mockMvc.perform(post("/api/admin/audit-logs")
                            .header("Authorization", "Bearer " + adminToken())
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isMethodNotAllowed());
        }

        @Test
        @DisplayName("filtering by an unknown action returns an empty page, not an error")
        void unknownActionFilterIsEmpty() throws Exception {
            JsonNode page = auditPage("action=NO_SUCH_ACTION_EVER");
            assertThat(page.path("content").size()).isZero();
            assertThat(page.path("totalElements").asLong()).isZero();
        }
    }

    // ==================================================================
    // Accounts and roles
    // ==================================================================

    @Nested
    @DisplayName("accounts and roles")
    class Accounts {

        @Test
        @DisplayName("an admin can create a staff account that can then log in")
        void createdStaffCanLogIn() throws Exception {
            String email = "p7t-hire-" + COUNTER.incrementAndGet() + "@example.com";
            String body = objectMapper.writeValueAsString(Map.of(
                    "fullName", "New Hire", "email", email, "phone", "9811111111",
                    "role", "STAFF", "password", "Password@123", "gender", "Female", "age", 30));

            mockMvc.perform(post("/api/admin/users")
                            .header("Authorization", "Bearer " + adminToken())
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.role").value("STAFF"))
                    .andExpect(jsonPath("$.data.active").value(true))
                    .andExpect(jsonPath("$.data.temporaryAccount").value(false));

            // The account is real: the password set at creation works.
            String token = login(email, "Password@123");
            assertThat(token).isNotBlank();

            assertThat(auditPage("action=USER_CREATED&search=" + email).path("totalElements").asLong())
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("a duplicate email is rejected")
        void duplicateEmailIsRejected() throws Exception {
            String email = "p7t-dup-" + COUNTER.incrementAndGet() + "@example.com";
            String body = objectMapper.writeValueAsString(Map.of(
                    "fullName", "First", "email", email, "phone", "9822222222",
                    "role", "PASSENGER", "password", "Password@123"));

            mockMvc.perform(post("/api/admin/users")
                            .header("Authorization", "Bearer " + adminToken())
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk());

            mockMvc.perform(post("/api/admin/users")
                            .header("Authorization", "Bearer " + adminToken())
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("a role change is recorded as a role change, and takes effect")
        void roleChangeIsRecorded() throws Exception {
            String body = objectMapper.writeValueAsString(Map.of("role", "STAFF"));
            mockMvc.perform(put("/api/admin/users/" + passengerId)
                            .header("Authorization", "Bearer " + adminToken())
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.role").value("STAFF"));

            assertThat(userRepository.findById(passengerId).orElseThrow().getRole()).isEqualTo(RoleName.STAFF);
            assertThat(auditPage("action=USER_ROLE_CHANGED&entityId=" + passengerId)
                    .path("totalElements").asLong()).isEqualTo(1);
        }

        @Test
        @DisplayName("an admin cannot change their own role")
        void anAdminCannotDemoteThemselves() throws Exception {
            String body = objectMapper.writeValueAsString(Map.of("role", "PASSENGER"));
            mockMvc.perform(put("/api/admin/users/" + adminId)
                            .header("Authorization", "Bearer " + adminToken())
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isForbidden());

            assertThat(userRepository.findById(adminId).orElseThrow().getRole()).isEqualTo(RoleName.ADMIN);
        }

        @Test
        @DisplayName("an admin cannot deactivate their own account")
        void anAdminCannotDeactivateThemselves() throws Exception {
            mockMvc.perform(patch("/api/admin/users/" + adminId + "/active")
                            .param("active", "false")
                            .header("Authorization", "Bearer " + adminToken()))
                    .andExpect(status().isForbidden());

            assertThat(Boolean.TRUE.equals(userRepository.findById(adminId).orElseThrow().getActive())).isTrue();
        }

        @Test
        @DisplayName("the user list filters by role and pages")
        void userListFiltersAndPages() throws Exception {
            mockMvc.perform(get("/api/admin/users")
                            .param("role", "STAFF")
                            .param("size", "1")
                            .header("Authorization", "Bearer " + adminToken()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.size").value(1))
                    .andExpect(jsonPath("$.data.content[0].role").value("STAFF"));
        }
    }

    // ==================================================================
    // Reports
    // ==================================================================

    @Nested
    @DisplayName("reports")
    class Reports {

        @Test
        @DisplayName("the operations report names its window and carries the whole system count")
        void operationsReportHasAWindowAndTotals() throws Exception {
            mockMvc.perform(get("/api/admin/reports/operations")
                            .header("Authorization", "Bearer " + adminToken()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalUsers").isNumber())
                    .andExpect(jsonPath("$.data.byStatus").isMap());
        }

        @Test
        @DisplayName("a reversed date range is swapped rather than returned empty")
        void aReversedRangeIsSwapped() throws Exception {
            mockMvc.perform(get("/api/admin/reports/operations")
                            .param("from", "2030-01-02")
                            .param("to", "2030-01-01")
                            .header("Authorization", "Bearer " + adminToken()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.from").value("2030-01-01"))
                    .andExpect(jsonPath("$.data.to").value("2030-01-02"));
        }

        @Test
        @DisplayName("the refund report always names every refund state")
        void refundReportNamesEveryState() throws Exception {
            mockMvc.perform(get("/api/admin/reports/refunds")
                            .header("Authorization", "Bearer " + adminToken()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.byStatus.PENDING").exists())
                    .andExpect(jsonPath("$.data.byStatus.PROCESSED").exists())
                    .andExpect(jsonPath("$.data.byStatus.FAILED").exists());
        }
    }

    // ==================================================================
    // Train configuration: routes and coaches
    // ==================================================================

    @Nested
    @DisplayName("train configuration")
    class TrainConfiguration {

        @Test
        @DisplayName("a route can be read back in order after being replaced")
        void routeRoundTrips() throws Exception {
            long trainId = createBareTrain();
            String body = objectMapper.writeValueAsString(List.of(
                    stop(ndls, 1), stop(lko, 2)));
            mockMvc.perform(put("/api/admin/trains/" + trainId + "/route")
                            .header("Authorization", "Bearer " + adminToken())
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(2))
                    .andExpect(jsonPath("$.data[0].stationCode").value("NDLS"))
                    .andExpect(jsonPath("$.data[1].stationCode").value("LKO"));

            mockMvc.perform(get("/api/admin/trains/" + trainId + "/route")
                            .header("Authorization", "Bearer " + adminToken()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].sequenceNumber").value(1))
                    .andExpect(jsonPath("$.data[1].sequenceNumber").value(2));

            assertThat(routeStationRepository.findByTrainIdOrderBySequenceNumberAsc(trainId)).hasSize(2);
        }

        @Test
        @DisplayName("a route with a gap in its numbering is rejected")
        void routeGapsAreRejected() throws Exception {
            long trainId = createBareTrain();
            String body = objectMapper.writeValueAsString(List.of(stop(ndls, 1), stop(lko, 3)));
            mockMvc.perform(put("/api/admin/trains/" + trainId + "/route")
                            .header("Authorization", "Bearer " + adminToken())
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("the same station twice on one route is rejected")
        void duplicateStationsAreRejected() throws Exception {
            long trainId = createBareTrain();
            String body = objectMapper.writeValueAsString(List.of(stop(ndls, 1), stop(ndls, 2)));
            mockMvc.perform(put("/api/admin/trains/" + trainId + "/route")
                            .header("Authorization", "Bearer " + adminToken())
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("a coach's seat class cannot change once it exists")
        void seatClassIsImmutable() throws Exception {
            long trainId = createBareTrain();
            long coachId = addCoach(trainId, "P7A", "SECOND_SEATING", 40);

            String body = objectMapper.writeValueAsString(Map.of(
                    "coachCode", "P7A", "seatClass", "CHAIR_CAR",
                    "totalSeats", 40, "baseFare", 500));
            mockMvc.perform(put("/api/admin/coaches/" + coachId)
                            .header("Authorization", "Bearer " + adminToken())
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("growing a coach appends seats; it never regenerates existing ones")
        void growingACoachAppendsSeats() throws Exception {
            long trainId = createBareTrain();
            long coachId = addCoach(trainId, "P7B", "SECOND_SEATING", 40);

            String body = objectMapper.writeValueAsString(Map.of(
                    "coachCode", "P7B", "seatClass", "SECOND_SEATING",
                    "totalSeats", 48, "baseFare", 500));
            mockMvc.perform(put("/api/admin/coaches/" + coachId)
                            .header("Authorization", "Bearer " + adminToken())
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalSeats").value(48));

            assertThat(seatRepository.countByCoachId(coachId)).isEqualTo(48);
            assertThat(auditPage("action=COACH_UPDATED&entityId=" + coachId)
                    .path("totalElements").asLong()).isEqualTo(1);
        }
    }

    // ==================================================================
    // Helpers
    // ==================================================================

    private String adminToken() throws Exception {
        return login(ADMIN_EMAIL, ADMIN_PASSWORD);
    }

    private long createStation(String code) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "code", code, "name", "Test " + code, "city", "Test City", "state", "Test State"));
        MvcResult result = mockMvc.perform(post("/api/admin/stations")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("id").asLong();
    }

    private void setStationActive(long stationId, boolean active) throws Exception {
        mockMvc.perform(patch("/api/admin/stations/" + stationId + "/active")
                        .param("active", String.valueOf(active))
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk());
    }

    private long createBareTrain() throws Exception {
        String number = String.format("%05d", 70000 + COUNTER.incrementAndGet() % 20000);
        String body = objectMapper.writeValueAsString(Map.of(
                "trainNumber", number, "name", "P7 Express " + number, "type", "EXPRESS",
                "sourceStationId", ndls, "destinationStationId", lko,
                "departureTime", "06:00", "arrivalTime", "14:00",
                "durationMinutes", 480, "runsOn", "MON,TUE,WED,THU,FRI,SAT,SUN"));
        MvcResult result = mockMvc.perform(post("/api/admin/trains")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn();
        long trainId = objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("id").asLong();
        createdTrainIds.add(trainId);
        return trainId;
    }

    private long addCoach(long trainId, String code, String seatClass, int seats) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "coachCode", code, "seatClass", seatClass, "totalSeats", seats,
                "baseFare", 500, "racCapacity", 0));
        MvcResult result = mockMvc.perform(post("/api/admin/trains/" + trainId + "/coaches")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("id").asLong();
    }

    private Map<String, Object> stop(long stationId, int sequence) {
        return Map.of(
                "stationId", stationId, "sequenceNumber", sequence,
                "arrivalTime", "06:00", "departureTime", "06:10",
                "haltMinutes", 10, "distanceFromSourceKm", sequence * 100);
    }

    private JsonNode auditPage(String query) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/admin/audit-logs?" + query)
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private int indexOfSummaryContaining(JsonNode content, String needle) {
        for (int i = 0; i < content.size(); i++) {
            if (content.get(i).path("summary").asText().contains(needle)) {
                return i;
            }
        }
        return -1;
    }

    private String stationCode() {
        int n = COUNTER.incrementAndGet();
        return "Q" + (char) ('A' + (n / 26) % 26) + (char) ('A' + n % 26);
    }

    private Long stationId(String code) {
        return stationRepository.findByCode(code).orElseThrow().getId();
    }

    private Long newUser(String email, String fullName, RoleName role) {
        return transactionTemplate.execute(status -> userRepository.saveAndFlush(User.builder()
                .fullName(fullName)
                .email(email)
                .phone("98" + String.format("%08d", PHONE_COUNTER.incrementAndGet()))
                .password(passwordEncoder.encode("Password@123"))
                .role(role)
                .active(true)
                .temporaryAccount(false)
                .build()).getId());
    }

    private String tokenForUser(Long id) throws Exception {
        User user = userRepository.findById(id).orElseThrow();
        return login(user.getEmail(), "Password@123");
    }

    private String login(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("email", email, "password", password))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("token").asText();
    }
}

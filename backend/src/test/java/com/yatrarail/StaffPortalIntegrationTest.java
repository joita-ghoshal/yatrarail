package com.yatrarail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yatrarail.dto.booking.BookingResponseDto;
import com.yatrarail.dto.booking.CreateBookingRequest;
import com.yatrarail.dto.booking.PassengerDto;
import com.yatrarail.dto.booking.PassengerResultDto;
import com.yatrarail.dto.booking.ProcessPaymentRequest;
import com.yatrarail.dto.staff.AssistanceRequestBody;
import com.yatrarail.entity.*;
import com.yatrarail.exception.BadRequestException;
import com.yatrarail.exception.ConflictException;
import com.yatrarail.exception.ResourceNotFoundException;
import com.yatrarail.repository.*;
import com.yatrarail.service.AssistanceService;
import com.yatrarail.service.BookingService;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 6 — the railway staff portal: gate check-in, incident reporting and assistance requests.
 * <p>
 * The behaviour under test is the rule that makes this portal safe to hand to a conductor: what
 * staff record at the gate is an <b>operational fact only</b>. Marking a passenger NO_SHOW writes a
 * boarding state and nothing else — it must not refund, must not cancel, and must not move the
 * booking's financial state. Several tests below exist purely to hold that line.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StaffPortalIntegrationTest {

    private static final AtomicInteger COUNTER = new AtomicInteger();
    private static final AtomicInteger PHONE_COUNTER = new AtomicInteger(2_000);

    @Autowired
    private BookingService bookingService;
    @Autowired
    private AssistanceService assistanceService;
    @Autowired
    private com.yatrarail.service.BoardingService boardingService;
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private BookingRepository bookingRepository;
    @Autowired
    private BookingPassengerRepository bookingPassengerRepository;
    @Autowired
    private SeatReservationRepository seatReservationRepository;
    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private PaymentAttemptRepository paymentAttemptRepository;
    @Autowired
    private CancellationRepository cancellationRepository;
    @Autowired
    private TrainIncidentRepository incidentRepository;
    @Autowired
    private AssistanceRequestRepository assistanceRequestRepository;
    @Autowired
    private NotificationRepository notificationRepository;
    @Autowired
    private CoachRepository coachRepository;
    @Autowired
    private SeatRepository seatRepository;
    @Autowired
    private TrainRepository trainRepository;
    @Autowired
    private StationRepository stationRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private TransactionTemplate transactionTemplate;

    private Long passengerId;
    private Long strangerId;
    private Long staffId;
    private Train rajdhani;
    private Coach thirdAcCoach;
    private Long ndls;
    private Long lko;

    @BeforeEach
    void setUp() {
        resetState();
        int n = COUNTER.incrementAndGet();
        passengerId = newUser("p6t-owner-" + n + "@example.com", "Platform Owner", RoleName.PASSENGER);
        strangerId = newUser("p6t-stranger-" + n + "@example.com", "Stranger", RoleName.PASSENGER);
        staffId = newUser("p6t-conductor-" + n + "@example.com", "Conductor", RoleName.STAFF);

        rajdhani = trainRepository.findByTrainNumber("12301").orElseThrow();
        reactivateSeededThirdAcCoaches();
        thirdAcCoach = coachRepository.findByTrainIdAndSeatClass(rajdhani.getId(), SeatClass.THIRD_AC)
                .orElseThrow();
        ndls = stationId("NDLS");
        lko = stationId("LKO");
    }

    private void resetState() {
        transactionTemplate.executeWithoutResult(status -> {
            // Newest Phase 6 tables first: they reference bookings and passengers.
            assistanceRequestRepository.deleteAll();
            incidentRepository.deleteAll();
            cancellationRepository.deleteAll();
            paymentAttemptRepository.deleteAll();
            paymentRepository.deleteAll();
            seatReservationRepository.deleteAll();
            bookingRepository.deleteAll();
            coachRepository.findAll().stream()
                    .filter(c -> c.getCoachCode() != null && c.getCoachCode().startsWith("Z"))
                    .collect(java.util.stream.Collectors.toList())
                    .forEach(coach -> {
                        seatRepository.deleteByCoachId(coach.getId());
                        coachRepository.delete(coach);
                    });
            seatRepository.flush();
            coachRepository.flush();
        });
    }

    // ==================================================================
    // Gate check-in
    // ==================================================================

    @Nested
    @DisplayName("gate check-in")
    class GateCheckIn {

        @Test
        @DisplayName("starts every passenger at NOT_BOARDED rather than null")
        void aFreshPassengerHasNotBoarded() {
            BookingResponseDto booking = createAndPay(passengerId, LocalDate.now().plusDays(2), 1);

            BoardingState passenger = reload(booking, 0);

            assertThat(passenger.status()).isEqualTo(BoardingStatus.NOT_BOARDED);
            assertThat(passenger.markedAt()).isNull();
            assertThat(passenger.markedById()).isNull();
        }

        @Test
        @DisplayName("records BOARDED and names the staff member who scanned")
        void staffCanMarkAPassengerBoarded() throws Exception {
            BookingResponseDto booking = createAndPay(passengerId, LocalDate.now().plusDays(2), 1);
            Long bpId = passengerIdOf(booking, 0);
            String token = tokenForStaff(staffId);

            mockMvc.perform(patch("/api/staff/passengers/{id}/boarding", bpId)
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"BOARDED\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("BOARDED"))
                    .andExpect(jsonPath("$.data.bookingReference").value(booking.getBookingReference()));

            BoardingState passenger = reload(booking, 0);
            assertThat(passenger.status()).isEqualTo(BoardingStatus.BOARDED);
            assertThat(passenger.markedAt()).isNotNull();
            assertThat(passenger.markedById()).isEqualTo(staffId);

            // The test would still pass if markBoardingWithDetail returned early without ever
            // touching the entity, so assert the state actually changed on this row.
            assertThat(passenger.status()).isNotEqualTo(BoardingStatus.NOT_BOARDED);
        }

        @Test
        @DisplayName("the service itself writes the state, not just the HTTP response")
        void theServicePersistsTheChange() {
            BookingResponseDto booking = createAndPay(passengerId, LocalDate.now().plusDays(2), 1);
            Long bpId = passengerIdOf(booking, 0);
            com.yatrarail.dto.staff.BoardingRequest request = new com.yatrarail.dto.staff.BoardingRequest();
            request.setStatus("NO_SHOW");
            assertThat(boardingService.currentBoardingStatus(bpId)).isEqualTo(BoardingStatus.NOT_BOARDED);

            boardingService.markBoardingWithDetail(bpId, request, staffId);

            // Read back through a fresh service call: if the implementation only built the response
            // DTO and never saved, this would still be NOT_BOARDED.
            assertThat(boardingService.currentBoardingStatus(bpId)).isEqualTo(BoardingStatus.NO_SHOW);
        }

        @Test
        @DisplayName("a mis-scan can be corrected, and the correction is attributed")
        void aMisScanCanBeCorrected() throws Exception {
            BookingResponseDto booking = createAndPay(passengerId, LocalDate.now().plusDays(2), 1);
            Long bpId = passengerIdOf(booking, 0);
            String token = tokenForStaff(staffId);

            mockMvc.perform(patch("/api/staff/passengers/{id}/boarding", bpId)
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"status\":\"NO_SHOW\"}")).andExpect(status().isOk());

            mockMvc.perform(patch("/api/staff/passengers/{id}/boarding", bpId)
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"BOARDED\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("BOARDED"));

            assertThat(reload(booking, 0).status()).isEqualTo(BoardingStatus.BOARDED);
        }

        @Test
        @DisplayName("marking the same state twice is a no-op, not an error")
        void aDoubleTapIsHarmless() throws Exception {
            BookingResponseDto booking = createAndPay(passengerId, LocalDate.now().plusDays(2), 1);
            Long bpId = passengerIdOf(booking, 0);
            String token = tokenForStaff(staffId);

            for (int i = 0; i < 3; i++) {
                mockMvc.perform(patch("/api/staff/passengers/{id}/boarding", bpId)
                                .header("Authorization", "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"status\":\"BOARDED\"}"))
                        .andExpect(status().isOk());
            }

            assertThat(reload(booking, 0).status()).isEqualTo(BoardingStatus.BOARDED);
        }

        @Test
        @DisplayName("NO SHOW moves no money and cancels nothing")
        void noShowNeverTouchesMoney() throws Exception {
            LocalDate journeyDate = LocalDate.now().plusDays(9);
            BookingResponseDto booking = createAndPay(passengerId, journeyDate, 1);
            Long bpId = passengerIdOf(booking, 0);
            long cancellationsBefore = cancellationRepository.count();
            String token = tokenForStaff(staffId);

            mockMvc.perform(patch("/api/staff/passengers/{id}/boarding", bpId)
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"NO_SHOW\"}"))
                    .andExpect(status().isOk());

            Booking reloaded = bookingRepository.findById(booking.getId()).orElseThrow();
            assertThat(reloaded.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
            assertThat(reloaded.getTotalFare()).isEqualByComparingTo(booking.getTotalFare());
            assertThat(cancellationRepository.count()).isEqualTo(cancellationsBefore);
            assertThat(paymentRepository.count()).isEqualTo(1);
        }

        @Test
        @DisplayName("an unknown boarding state is a 400, not a 500")
        void anUnknownBoardingStateIsRejected() throws Exception {
            BookingResponseDto booking = createAndPay(passengerId, LocalDate.now().plusDays(2), 1);
            Long bpId = passengerIdOf(booking, 0);
            String token = tokenForStaff(staffId);

            mockMvc.perform(patch("/api/staff/passengers/{id}/boarding", bpId)
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"TELEPORTED\"}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("the manifest carries each passenger's boarding state and id")
        void theManifestCarriesBoardingState() throws Exception {
            LocalDate journeyDate = LocalDate.now().plusDays(3);
            BookingResponseDto booking = createAndPay(passengerId, journeyDate, 2);
            passengerIdOf(booking, 0);
            String token = tokenForStaff(staffId);

            mockMvc.perform(patch("/api/staff/passengers/{id}/boarding", passengerIdOf(booking, 0))
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"BOARDED\"}"))
                    .andExpect(status().isOk());

            mockMvc.perform(get("/api/staff/trains/{id}/passengers", rajdhani.getId())
                            .header("Authorization", "Bearer " + token)
                            .param("journeyDate", journeyDate.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].passengers[0].boarding.status").value("BOARDED"))
                    .andExpect(jsonPath("$.data[0].passengers[0].boarding.markedByName").value("Conductor"))
                    .andExpect(jsonPath("$.data[0].passengers[0].id").isNumber());
        }

        @Test
        @DisplayName("the dashboard counts boarding states for the day")
        void theDashboardCountsBoardingStates() throws Exception {
            LocalDate journeyDate = LocalDate.now().plusDays(4);
            BookingResponseDto booking = createAndPay(passengerId, journeyDate, 2);
            String token = tokenForStaff(staffId);

            mockMvc.perform(patch("/api/staff/passengers/{id}/boarding", passengerIdOf(booking, 0))
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"BOARDED\"}"))
                    .andExpect(status().isOk());
            mockMvc.perform(patch("/api/staff/passengers/{id}/boarding", passengerIdOf(booking, 1))
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"NO_SHOW\"}"))
                    .andExpect(status().isOk());

            mockMvc.perform(get("/api/staff/dashboard")
                            .header("Authorization", "Bearer " + token)
                            .param("journeyDate", journeyDate.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalPassengers").value(2))
                    .andExpect(jsonPath("$.data.boardedPassengers").value(1))
                    .andExpect(jsonPath("$.data.noShowPassengers").value(1))
                    .andExpect(jsonPath("$.data.notBoardedPassengers").value(0));
        }

        @Test
        @DisplayName("a passenger whose booking was cancelled cannot be marked as boarded")
        void aCancelledPassengerCannotBeBoarded() throws Exception {
            // The manifest only shows travelling bookings, so a stale UI tab must not be able to
            // check in somebody whose seat was sold again.
            LocalDate journeyDate = LocalDate.now().plusDays(11);
            BookingResponseDto booking = createAndPay(passengerId, journeyDate, 1);
            Long bpId = passengerIdOf(booking, 0);
            bookingService.cancelBooking(passengerId, booking.getId(),
                    new com.yatrarail.dto.booking.CancelBookingRequest());
            String token = tokenForStaff(staffId);

            mockMvc.perform(patch("/api/staff/passengers/{id}/boarding", bpId)
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"BOARDED\"}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("the dashboard counts a whole journey day, not one train")
        void theDashboardIsNotScopedToASingleTrain() throws Exception {
            // Regression guard: the passenger tally must sum across every train. Passing a null
            // train id to a `train.id = :id` query would match nothing and report zero forever.
            LocalDate journeyDate = LocalDate.now().plusDays(5);
            createAndPay(passengerId, journeyDate, 3);
            String token = tokenForStaff(staffId);

            mockMvc.perform(get("/api/staff/dashboard")
                            .header("Authorization", "Bearer " + token)
                            .param("journeyDate", journeyDate.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalPassengers").value(3));
        }
    }

    // ==================================================================
    // Authorization
    // ==================================================================

    @Nested
    @DisplayName("authorization")
    class Authorization {

        @Test
        @DisplayName("a passenger cannot mark boarding or read the dashboard")
        void aPassengerIsRefusedStaffEndpoints() throws Exception {
            BookingResponseDto booking = createAndPay(passengerId, LocalDate.now().plusDays(2), 1);
            String passengerToken = tokenForUser(passengerId);

            mockMvc.perform(patch("/api/staff/passengers/{id}/boarding", passengerIdOf(booking, 0))
                            .header("Authorization", "Bearer " + passengerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"BOARDED\"}"))
                    .andExpect(status().isForbidden());

            mockMvc.perform(get("/api/staff/dashboard").header("Authorization", "Bearer " + passengerToken))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("an anonymous caller is refused")
        void anAnonymousCallerIsRefused() throws Exception {
            mockMvc.perform(get("/api/staff/dashboard")).andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("an admin may use the staff portal")
        void anAdminMayUseTheStaffPortal() throws Exception {
            String adminToken = login("admin@yatrarail.demo", "Admin@123");

            mockMvc.perform(get("/api/staff/dashboard").header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk());
        }
    }

    // ==================================================================
    // Incidents
    // ==================================================================

    @Nested
    @DisplayName("incident reporting")
    class Incidents {

        @Test
        @DisplayName("staff file a delay and it starts OPEN and attributed")
        void staffCanFileAnIncident() throws Exception {
            String token = tokenForStaff(staffId);

            mockMvc.perform(post("/api/staff/incidents")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(java.util.Map.of(
                                    "trainId", rajdhani.getId(),
                                    "journeyDate", LocalDate.now().plusDays(1).toString(),
                                    "type", "DELAY",
                                    "severity", "MAJOR",
                                    "delayMinutes", 90,
                                    "description", "Held at NDLS for a signal failure"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("OPEN"))
                    .andExpect(jsonPath("$.data.reportedByName").value("Conductor"))
                    .andExpect(jsonPath("$.data.delayMinutes").value(90));

            assertThat(incidentRepository.count()).isEqualTo(1);
        }

        @Test
        @DisplayName("filing an incident tells the other staff about it")
        void filingAnIncidentNotifiesStaff() throws Exception {
            String token = tokenForStaff(staffId);

            mockMvc.perform(post("/api/staff/incidents")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(java.util.Map.of(
                                    "trainId", rajdhani.getId(),
                                    "journeyDate", LocalDate.now().plusDays(1).toString(),
                                    "type", "BREAKDOWN",
                                    "severity", "CRITICAL",
                                    "description", "Coach B2 failed at LKO"))))
                    .andExpect(status().isOk());

            long staffNotices = notificationRepository.findByUserIdOrderByCreatedAtDesc(staffId).stream()
                    .filter(n -> "STAFF_INCIDENT".equals(n.getType()))
                    .count();
            assertThat(staffNotices).isEqualTo(1);
        }

        @Test
        @DisplayName("a second open report of the same disruption is a 409")
        void aDuplicateOpenIncidentIsRefused() throws Exception {
            String token = tokenForStaff(staffId);
            String body = objectMapper.writeValueAsString(java.util.Map.of(
                    "trainId", rajdhani.getId(),
                    "journeyDate", LocalDate.now().plusDays(1).toString(),
                    "type", "DELAY",
                    "severity", "MINOR",
                    "description", "Running late"));

            mockMvc.perform(post("/api/staff/incidents")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body)).andExpect(status().isOk());

            mockMvc.perform(post("/api/staff/incidents")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("the same disruption may be filed again once the first is resolved")
        void aResolvedDuplicateIsAllowed() throws Exception {
            String token = tokenForStaff(staffId);
            String body = objectMapper.writeValueAsString(java.util.Map.of(
                    "trainId", rajdhani.getId(),
                    "journeyDate", LocalDate.now().plusDays(1).toString(),
                    "type", "DELAY",
                    "severity", "MINOR",
                    "description", "Running late"));

            mockMvc.perform(post("/api/staff/incidents")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body)).andExpect(status().isOk());

            Long id = incidentRepository.findAll().get(0).getId();
            mockMvc.perform(patch("/api/staff/incidents/{id}", id)
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"RESOLVED\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.resolvedByName").value("Conductor"));

            mockMvc.perform(post("/api/staff/incidents")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("reopening an incident clears who resolved it")
        void reopeningClearsTheResolution() throws Exception {
            String token = tokenForStaff(staffId);
            mockMvc.perform(post("/api/staff/incidents")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(java.util.Map.of(
                                    "trainId", rajdhani.getId(),
                                    "journeyDate", LocalDate.now().plusDays(1).toString(),
                                    "type", "DELAY", "severity", "MINOR",
                                    "description", "Running late"))))
                    .andExpect(status().isOk());
            Long id = incidentRepository.findAll().get(0).getId();

            mockMvc.perform(patch("/api/staff/incidents/{id}", id)
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"status\":\"RESOLVED\"}")).andExpect(status().isOk());

            mockMvc.perform(patch("/api/staff/incidents/{id}", id)
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"ACKNOWLEDGED\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.resolvedByName").doesNotExist())
                    .andExpect(jsonPath("$.data.resolvedAt").doesNotExist());
        }

        @Test
        @DisplayName("an unknown incident type is a 400")
        void anUnknownIncidentTypeIsRejected() throws Exception {
            String token = tokenForStaff(staffId);

            mockMvc.perform(post("/api/staff/incidents")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(java.util.Map.of(
                                    "trainId", rajdhani.getId(),
                                    "journeyDate", LocalDate.now().plusDays(1).toString(),
                                    "type", "ALIEN_INVASION", "severity", "MINOR",
                                    "description", "Nope"))))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("a blank description is refused")
        void aBlankDescriptionIsRefused() throws Exception {
            String token = tokenForStaff(staffId);

            mockMvc.perform(post("/api/staff/incidents")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(java.util.Map.of(
                                    "trainId", rajdhani.getId(),
                                    "journeyDate", LocalDate.now().plusDays(1).toString(),
                                    "type", "DELAY", "severity", "MINOR",
                                    "description", "   "))))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("a passenger cannot file an incident")
        void aPassengerCannotFileAnIncident() throws Exception {
            mockMvc.perform(post("/api/staff/incidents")
                            .header("Authorization", "Bearer " + tokenForUser(passengerId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(java.util.Map.of(
                                    "trainId", rajdhani.getId(),
                                    "journeyDate", LocalDate.now().plusDays(1).toString(),
                                    "type", "DELAY", "severity", "MINOR",
                                    "description", "Not mine to report"))))
                    .andExpect(status().isForbidden());
        }
    }

    // ==================================================================
    // Assistance requests
    // ==================================================================

    @Nested
    @DisplayName("assistance requests")
    class Assistance {

        @Test
        @DisplayName("a passenger asks for help for one of their own passengers")
        void aPassengerCanRaiseARequest() throws Exception {
            BookingResponseDto booking = createAndPay(passengerId, LocalDate.now().plusDays(2), 2);
            AssistanceRequestBody body = new AssistanceRequestBody();
            body.setPassengerId(passengerIdOf(booking, 0));
            body.setCategory("WHEELCHAIR");
            body.setDetails("Needs step-free access at both ends");

            mockMvc.perform(post("/api/bookings/{id}/assistance-requests", booking.getId())
                            .header("Authorization", "Bearer " + tokenForUser(passengerId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("OPEN"))
                    .andExpect(jsonPath("$.data.category").value("WHEELCHAIR"))
                    .andExpect(jsonPath("$.data.bookingReference").value(booking.getBookingReference()));
        }

        @Test
        @DisplayName("raising a request notifies staff")
        void raisingARequestNotifiesStaff() throws Exception {
            BookingResponseDto booking = createAndPay(passengerId, LocalDate.now().plusDays(2), 1);
            AssistanceRequestBody body = new AssistanceRequestBody();
            body.setPassengerId(passengerIdOf(booking, 0));
            body.setCategory("LUGGAGE");

            mockMvc.perform(post("/api/bookings/{id}/assistance-requests", booking.getId())
                    .header("Authorization", "Bearer " + tokenForUser(passengerId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isOk());

            assertThat(notificationRepository.findByUserIdOrderByCreatedAtDesc(staffId).stream()
                    .filter(n -> "ASSISTANCE_REQUEST".equals(n.getType()))
                    .count()).isEqualTo(1);
        }

        @Test
        @DisplayName("a stranger gets 404 for somebody else's passenger, not 403")
        void aStrangerCannotSeeAnotherPassengersBooking() throws Exception {
            BookingResponseDto booking = createAndPay(passengerId, LocalDate.now().plusDays(2), 1);
            AssistanceRequestBody body = new AssistanceRequestBody();
            body.setPassengerId(passengerIdOf(booking, 0));
            body.setCategory("MEDICAL");

            mockMvc.perform(post("/api/bookings/{id}/assistance-requests", booking.getId())
                            .header("Authorization", "Bearer " + tokenForUser(strangerId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    // 404, not 403: the endpoint must not confirm that this booking exists.
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("asking twice for the same help is a 409, not a duplicate ticket")
        void aDuplicateRequestIsRefused() throws Exception {
            BookingResponseDto booking = createAndPay(passengerId, LocalDate.now().plusDays(2), 1);
            AssistanceRequestBody body = new AssistanceRequestBody();
            body.setPassengerId(passengerIdOf(booking, 0));
            body.setCategory("LUGGAGE");
            String json = objectMapper.writeValueAsString(body);

            mockMvc.perform(post("/api/bookings/{id}/assistance-requests", booking.getId())
                    .header("Authorization", "Bearer " + tokenForUser(passengerId))
                    .contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isOk());

            mockMvc.perform(post("/api/bookings/{id}/assistance-requests", booking.getId())
                            .header("Authorization", "Bearer " + tokenForUser(passengerId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json))
                    .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("a resolved request does not block asking again")
        void aResolvedRequestCanBeRaisedAgain() throws Exception {
            BookingResponseDto booking = createAndPay(passengerId, LocalDate.now().plusDays(2), 1);
            AssistanceRequestBody body = new AssistanceRequestBody();
            body.setPassengerId(passengerIdOf(booking, 0));
            body.setCategory("LUGGAGE");

            mockMvc.perform(post("/api/bookings/{id}/assistance-requests", booking.getId())
                            .header("Authorization", "Bearer " + tokenForUser(passengerId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isOk());

            Long requestId = assistanceRequestRepository.findAll().get(0).getId();
            mockMvc.perform(patch("/api/staff/assistance/{id}", requestId)
                            .header("Authorization", "Bearer " + tokenForStaff(staffId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"RESOLVED\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.assignedToName").value("Conductor"));

            mockMvc.perform(post("/api/bookings/{id}/assistance-requests", booking.getId())
                            .header("Authorization", "Bearer " + tokenForUser(passengerId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("asking for help on a cancelled journey is refused")
        void aCancelledJourneyCannotRequestAssistance() throws Exception {
            LocalDate journeyDate = LocalDate.now().plusDays(9);
            BookingResponseDto booking = createAndPay(passengerId, journeyDate, 1);
            Long bpId = passengerIdOf(booking, 0);
            bookingService.cancelBooking(passengerId, booking.getId(),
                    new com.yatrarail.dto.booking.CancelBookingRequest());

            AssistanceRequestBody body = new AssistanceRequestBody();
            body.setPassengerId(bpId);
            body.setCategory("WHEELCHAIR");

            mockMvc.perform(post("/api/bookings/{id}/assistance-requests", booking.getId())
                            .header("Authorization", "Bearer " + tokenForUser(passengerId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("an unknown category is a 400")
        void anUnknownCategoryIsRejected() throws Exception {
            BookingResponseDto booking = createAndPay(passengerId, LocalDate.now().plusDays(2), 1);
            AssistanceRequestBody body = new AssistanceRequestBody();
            body.setPassengerId(passengerIdOf(booking, 0));
            body.setCategory("TELEPORTATION");

            mockMvc.perform(post("/api/bookings/{id}/assistance-requests", booking.getId())
                            .header("Authorization", "Bearer " + tokenForUser(passengerId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("the staff queue shows the seat to walk to")
        void theStaffQueueIsActionable() throws Exception {
            LocalDate journeyDate = LocalDate.now().plusDays(6);
            BookingResponseDto booking = createAndPay(passengerId, journeyDate, 1);
            AssistanceRequestBody body = new AssistanceRequestBody();
            body.setPassengerId(passengerIdOf(booking, 0));
            body.setCategory("WHEELCHAIR");

            mockMvc.perform(post("/api/bookings/{id}/assistance-requests", booking.getId())
                    .header("Authorization", "Bearer " + tokenForUser(passengerId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isOk());

            mockMvc.perform(get("/api/staff/assistance")
                            .header("Authorization", "Bearer " + tokenForStaff(staffId))
                            .param("journeyDate", journeyDate.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].passengerName").exists())
                    .andExpect(jsonPath("$.data[0].coachCode").exists())
                    .andExpect(jsonPath("$.data[0].trainNumber").value("12301"));
        }
    }

    // ==================================================================
    // Helpers
    // ==================================================================

    private BookingResponseDto createAndPay(Long uid, LocalDate journeyDate, int passengers) {
        BookingResponseDto dto = bookingService.createBooking(uid, request(journeyDate, passengers), null);
        ProcessPaymentRequest payment = new ProcessPaymentRequest();
        payment.setMethod("UPI");
        payment.setSimulateSuccess(true);
        return bookingService.processPayment(uid, dto.getId(), payment, null);
    }

    private CreateBookingRequest request(LocalDate journeyDate, int passengers) {
        CreateBookingRequest request = new CreateBookingRequest();
        request.setTrainId(rajdhani.getId());
        request.setCoachId(thirdAcCoach.getId());
        request.setSourceStationId(ndls);
        request.setDestinationStationId(lko);
        request.setJourneyDate(journeyDate);
        List<PassengerDto> people = new ArrayList<>();
        for (int i = 1; i <= passengers; i++) {
            PassengerDto p = new PassengerDto();
            p.setFullName("Rider " + i + " " + COUNTER.incrementAndGet());
            p.setAge(30);
            p.setGender("MALE");
            p.setBerthPreference("NO_PREFERENCE");
            p.setQuota(QuotaType.GENERAL.name());
            people.add(p);
        }
        request.setPassengers(people);
        return request;
    }

    /**
     * Reads a passenger back inside a transaction.
     * <p>
     * {@code Booking.passengers} is a lazy collection and {@code open-in-view=false}, so touching it
     * outside a session throws {@code LazyInitializationException}. The value is copied into a
     * detached record rather than returned as the live entity, which would silently pass as long as
     * the lazy proxy is never dereferenced.
     */
    private BoardingState reload(BookingResponseDto dto, int index) {
        return transactionTemplate.execute(status -> {
            BookingPassenger p = bookingRepository.findById(dto.getId()).orElseThrow().getPassengers()
                    .stream()
                    .sorted((a, b) -> Long.compare(a.getId(), b.getId()))
                    .skip(index).findFirst().orElseThrow();
            return new BoardingState(
                    p.getBoardingStatus(),
                    p.getBoardingMarkedAt(),
                    p.getBoardingMarkedBy() == null ? null : p.getBoardingMarkedBy().getId());
        });
    }

    private record BoardingState(BoardingStatus status,
                                 LocalDateTime markedAt,
                                 Long markedById) {
    }

    private Long passengerIdOf(BookingResponseDto dto, int index) {
        return dto.getPassengers().stream()
                .map(PassengerResultDto::getId)
                .sorted()
                .skip(index).findFirst().orElseThrow();
    }

    private void reactivateSeededThirdAcCoaches() {
        transactionTemplate.executeWithoutResult(status -> {
            for (Coach coach : coachRepository.findByTrainId(rajdhani.getId())) {
                if (coach.getSeatClass() == SeatClass.THIRD_AC && !Boolean.TRUE.equals(coach.getActive())) {
                    coach.setActive(true);
                    coachRepository.save(coach);
                }
            }
            coachRepository.flush();
        });
    }

    private Long stationId(String code) {
        return stationRepository.findByCode(code).orElseThrow().getId();
    }

    private Long newUser(String email, String fullName, RoleName role) {
        return transactionTemplate.execute(status -> userRepository.saveAndFlush(User.builder()
                .fullName(fullName)
                .email(email)
                .phone("97" + String.format("%08d", PHONE_COUNTER.incrementAndGet()))
                .password(passwordEncoder.encode("Password@123"))
                .role(role)
                .active(true)
                .temporaryAccount(false)
                .build())).getId();
    }

    private String tokenForUser(Long id) throws Exception {
        User user = userRepository.findById(id).orElseThrow();
        return login(user.getEmail(), "Password@123");
    }

    private String tokenForStaff(Long id) throws Exception {
        return tokenForUser(id);
    }

    private String login(String email, String password) throws Exception {
        var result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "email", email, "password", password))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        return json.path("data").path("token").asText();
    }
}
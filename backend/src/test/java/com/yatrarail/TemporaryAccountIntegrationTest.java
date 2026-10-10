package com.yatrarail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yatrarail.dto.booking.CreateBookingRequest;
import com.yatrarail.dto.booking.PassengerDto;
import com.yatrarail.dto.booking.ProcessPaymentRequest;
import com.yatrarail.entity.Coach;
import com.yatrarail.entity.RoleName;
import com.yatrarail.entity.Seat;
import com.yatrarail.entity.SeatClass;
import com.yatrarail.entity.Station;
import com.yatrarail.entity.Train;
import com.yatrarail.entity.User;
import com.yatrarail.repository.*;
import com.yatrarail.service.TemporaryAccountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 3 temporary one-time (guest) accounts.
 * <p>
 * The point of these tests is that the guest lifecycle is enforced by the <em>backend</em>, not by the
 * client or by the JWT clock alone. The two that matter most are
 * {@link #endedSessionIsRefusedEvenThoughItsTokenIsStillValid()} and
 * {@link #expiredSessionIsRefusedEvenThoughItsTokenIsStillValid()}: both revoke/expire the session in
 * the database while leaving the token untouched, and both must still produce a 401. Without those,
 * "logout" would be cosmetic until the token happened to lapse.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TemporaryAccountIntegrationTest {

    private static final AtomicInteger COUNTER = new AtomicInteger();
    private static final String TEST_COACH_PREFIX = "ZT";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private TemporaryAccountService temporaryAccountService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private BookingRepository bookingRepository;
    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private PaymentAttemptRepository paymentAttemptRepository;
    @Autowired
    private SeatReservationRepository seatReservationRepository;
    @Autowired
    private CancellationRepository cancellationRepository;
    @Autowired
    private CoachRepository coachRepository;
    @Autowired
    private TrainRepository trainRepository;
    @Autowired
    private StationRepository stationRepository;
    @Autowired
    private SeatRepository seatRepository;
    @Autowired
    private org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    private int n;
    private BookingFixture fixture;

    @BeforeEach
    void setUp() {
        n = COUNTER.incrementAndGet();
        fixture = null;
        resetBookingState();
    }

    // ------------------------------------------------------------------
    // Opening a session
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A guest session returns a short-lived token and honest session facts")
    void guestCanOpenSession() throws Exception {
        JsonNode session = createGuest("Guest One", "9876543210");

        assertThat(session.get("token").asText()).isNotBlank();
        JsonNode data = session.get("session");
        assertThat(data.get("role").asText()).isEqualTo("TEMP_PASSENGER");
        assertThat(data.get("accountType").asText()).isEqualTo("TEMPORARY");
        assertThat(data.get("sessionStatus").asText()).isEqualTo("ACTIVE");
        assertThat(data.get("expiresInSeconds").asLong()).isPositive();
        // Identity is synthetic and inside the reserved, non-routable domain.
        assertThat(data.get("email").asText()).endsWith("@guest.yatrarail.invalid");
    }

    @Test
    @DisplayName("A guest phone is optional, but a malformed one is rejected")
    void guestPhoneIsOptionalButValidated() throws Exception {
        assertThat(createGuest("No Phone", null).get("session").get("userId").asLong()).isPositive();

        Map<String, Object> bad = new HashMap<>();
        bad.put("fullName", "Bad Phone");
        bad.put("phone", "not-a-number");
        mockMvc.perform(post("/api/auth/temporary")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(bad)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("The reported deadline never exceeds the global JWT lifetime")
    void reportedDeadlineIsClampedToTheTokenLifetime() throws Exception {
        // ttl-minutes is 120 but the test JWT expires in 60, so the client must be told 60.
        long ttlSeconds = 120 * 60L;
        long jwtSeconds = 3600L;
        long reported = createGuest("Clamp Check", null).get("session").get("expiresInSeconds").asLong();

        assertThat(reported).isPositive().isLessThanOrEqualTo(Math.min(ttlSeconds, jwtSeconds));
    }

    @Test
    @DisplayName("The profile and the session endpoint report the same deadline")
    void profileAndSessionAgreeOnTheDeadline() throws Exception {
        JsonNode session = createGuest("Deadline Agreement", null);
        String token = session.get("token").asText();
        long fromSession = session.get("session").get("expiresInSeconds").asLong();

        MvcResult me = mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        LocalDateTime fromProfile = LocalDateTime.parse(objectMapper.readTree(me.getResponse().getContentAsString())
                .get("data").get("sessionExpiresAt").asText());

        // A client that reads one endpoint must not be told a different story by the other: a
        // countdown to the stored 2-hour deadline would outlive the 1-hour token. The two are
        // computed microseconds apart, so compare with a tolerance rather than for equality.
        LocalDateTime expected = LocalDateTime.now().plusSeconds(fromSession);
        assertThat(Duration.between(fromProfile, expected).abs().getSeconds()).isLessThanOrEqualTo(5);
        // Clamped to the JWT lifetime, comfortably inside the 2-hour stored TTL.
        assertThat(fromProfile).isBefore(LocalDateTime.now().plusHours(2));
        assertThat(fromSession).isPositive();
    }

    // ------------------------------------------------------------------
    // What a guest may and may not do
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A guest can use the passenger APIs a one-time booking needs")
    void guestCanUsePassengerApis() throws Exception {
        String token = createGuest("Booking Guest", null).get("token").asText();

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.temporaryAccount").value(true))
                .andExpect(jsonPath("$.data.role").value("TEMP_PASSENGER"))
                .andExpect(jsonPath("$.data.sessionExpiresAt").isNotEmpty());

        mockMvc.perform(get("/api/bookings").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/notifications").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("A guest can never reach staff or admin endpoints")
    void guestCannotReachStaffOrAdmin() throws Exception {
        String token = createGuest("Curious Guest", null).get("token").asText();

        mockMvc.perform(get("/api/admin/dashboard").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/staff/dashboard").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/users").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("A guest account can never be used to sign in")
    void guestCannotSignIn() throws Exception {
        JsonNode session = createGuest("Login Guest", null);
        String email = session.get("session").get("email").asText();
        String storedSecret = userRepository.findByEmail(email).orElseThrow().getPassword();

        // Even with the stored secret verbatim, login is refused: the credential is unusable by
        // design, and the account is never a sign-in identity.
        for (String candidate : List.of("Password@123", "password", storedSecret)) {
            Map<String, Object> body = new HashMap<>();
            body.put("email", email);
            body.put("password", candidate);
            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    @DisplayName("A guest has no password to change and no profile to edit")
    void guestCannotChangePasswordOrProfile() throws Exception {
        String token = createGuest("Profile Guest", null).get("token").asText();

        Map<String, Object> password = new HashMap<>();
        password.put("currentPassword", "Password@123");
        password.put("newPassword", "Different@123");
        mockMvc.perform(post("/api/auth/change-password")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(password)))
                .andExpect(status().isForbidden());

        Map<String, Object> profile = new HashMap<>();
        profile.put("fullName", "Renamed Guest");
        mockMvc.perform(put("/api/auth/profile")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(profile)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("A standard member cannot use the guest-session endpoints")
    void standardUserCannotUseGuestEndpoints() throws Exception {
        String memberToken = registerMember("member" + n + "@example.com", "Real Member");

        mockMvc.perform(get("/api/auth/temporary/session").header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/auth/temporary/session/end").header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Guest-only endpoints still require a token")
    void guestEndpointsRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/auth/temporary/session"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/auth/temporary/session/end"))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------
    // Backend-enforced lifecycle
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Ending a session revokes the token immediately")
    void endedSessionIsRefusedEvenThoughItsTokenIsStillValid() throws Exception {
        JsonNode session = createGuest("Ending Guest", null);
        String token = session.get("token").asText();

        // Sanity: the token works right up to the moment the session is ended.
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/temporary/session/end")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        // The JWT signature and exp are untouched, yet the session is dead.
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/bookings").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("An expired session is refused even though its token is still valid")
    void expiredSessionIsRefusedEvenThoughItsTokenIsStillValid() throws Exception {
        JsonNode session = createGuest("Expiring Guest", null);
        String token = session.get("token").asText();
        Long userId = session.get("session").get("userId").asLong();

        backdateDeadline(userId, LocalDateTime.now().minusMinutes(1));

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Introspection reports the real status and remaining time")
    void introspectionIsHonest() throws Exception {
        JsonNode live = createGuest("Introspect Live", null);
        long remaining = live.get("session").get("expiresInSeconds").asLong();
        assertThat(remaining).isPositive();

        mockMvc.perform(get("/api/auth/temporary/session")
                        .header("Authorization", "Bearer " + live.get("token").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sessionStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.data.expiresInSeconds").isNumber());

        // Expired: the status flips even though the caller can no longer read the session with the
        // token, so we read it straight from the database.
        JsonNode stale = createGuest("Introspect Stale", null);
        backdateDeadline(stale.get("session").get("userId").asLong(), LocalDateTime.now().minusMinutes(1));
        var dto = temporaryAccountService.introspect(stale.get("session").get("userId").asLong());
        assertThat(dto.getSessionStatus()).isEqualTo(TemporaryAccountService.STATUS_EXPIRED);
        assertThat(dto.getExpiresInSeconds()).isZero();

        // Revoked.
        JsonNode ended = createGuest("Introspect Ended", null);
        temporaryAccountService.endSession(ended.get("session").get("userId").asLong());
        var endedDto = temporaryAccountService.introspect(ended.get("session").get("userId").asLong());
        assertThat(endedDto.getSessionStatus()).isEqualTo(TemporaryAccountService.STATUS_REVOKED);
    }

    @Test
    @DisplayName("Cleanup revokes stale sessions but never deletes an account that owns bookings")
    void cleanupRevokesAndPurgesSafely() throws Exception {
        // A stale guest that owns a booking must survive the purge.
        JsonNode withBooking = createGuest("Owner Guest", null);
        Long ownerId = withBooking.get("session").get("userId").asLong();
        guestBooksOneSeat(withBooking.get("token").asText(), "Owner Passenger");

        // A stale guest with no bookings is safe to remove.
        JsonNode bookingless = createGuest("Bookingless Guest", null);
        Long bookinglessId = bookingless.get("session").get("userId").asLong();

        // A live session must be left completely alone.
        JsonNode active = createGuest("Active Guest", null);
        Long activeId = active.get("session").get("userId").asLong();

        backdateDeadline(ownerId, LocalDateTime.now().minusDays(30));
        backdateDeadline(bookinglessId, LocalDateTime.now().minusDays(30));

        int handled = temporaryAccountService.expireStaleSessions();

        assertThat(handled).isPositive();
        assertThat(userRepository.findById(activeId)).isPresent();
        assertThat(Boolean.TRUE.equals(userRepository.findById(activeId).orElseThrow().getActive())).isTrue();

        // The owner is deactivated but retained, so its ticket history stays reachable.
        var retained = userRepository.findById(ownerId).orElseThrow();
        assertThat(retained.getActive()).isFalse();
        assertThat(retained.getTempRevokedAt()).isNotNull();

        // The booking-less guest is past retention and is gone.
        assertThat(userRepository.findById(bookinglessId)).isEmpty();
    }

    // ------------------------------------------------------------------
    // Converting to a permanent account
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Converting promotes the guest in place and it can then sign in")
    void convertPromotesTheGuestInPlace() throws Exception {
        JsonNode session = createGuest("Converting Guest", "9000000001");
        String token = session.get("token").asText();
        Long userId = session.get("session").get("userId").asLong();
        String newEmail = "converted" + n + "@example.com";

        // A booking made as a guest must still be attached to the same account afterwards.
        Long bookingId = guestBooksOneSeat(token, "Converted Passenger");

        Map<String, Object> body = new HashMap<>();
        body.put("email", newEmail);
        body.put("password", "StrongPass@123");
        mockMvc.perform(post("/api/auth/temporary/convert")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("PASSENGER"))
                .andExpect(jsonPath("$.data.accountType").value("STANDARD"));

        // Same row, new identity - nothing was copied, so bookings never detach.
        User converted = userRepository.findById(userId).orElseThrow();
        assertThat(converted.getRole()).isEqualTo(RoleName.PASSENGER);
        assertThat(converted.getTemporaryAccount()).isFalse();
        assertThat(converted.getEmail()).isEqualTo(newEmail);
        assertThat(converted.getTempExpiresAt()).isNull();
        assertThat(bookingRepository.findById(bookingId).orElseThrow().getUser().getId()).isEqualTo(userId);

        // The new password is real, and the old synthetic address no longer works.
        Map<String, Object> login = new HashMap<>();
        login.put("email", newEmail);
        login.put("password", "StrongPass@123");
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("PASSENGER"));
    }

    @Test
    @DisplayName("Conversion refuses an email that already belongs to someone else")
    void convertRejectsAnEmailThatIsTaken() throws Exception {
        registerMember("taken" + n + "@example.com", "Already Registered");
        String token = createGuest("Confused Guest", null).get("token").asText();

        Map<String, Object> body = new HashMap<>();
        body.put("email", "taken" + n + "@example.com");
        body.put("password", "StrongPass@123");
        mockMvc.perform(post("/api/auth/temporary/convert")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("A weak conversion password is rejected by validation")
    void convertValidatesTheNewPassword() throws Exception {
        String token = createGuest("Weak Password Guest", null).get("token").asText();

        Map<String, Object> body = new HashMap<>();
        body.put("email", "weak" + n + "@example.com");
        body.put("password", "short");
        mockMvc.perform(post("/api/auth/temporary/convert")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("A finished session can no longer be converted")
    void convertIsRefusedAfterTheSessionEnded() throws Exception {
        JsonNode session = createGuest("Late Guest", null);
        String token = session.get("token").asText();
        temporaryAccountService.endSession(session.get("session").get("userId").asLong());

        Map<String, Object> body = new HashMap<>();
        body.put("email", "late" + n + "@example.com");
        body.put("password", "StrongPass@123");
        // 401, not 403: the request filter refuses the dead session before the controller is ever
        // reached. That ordering is the point - a revoked guest is unauthenticated, not merely
        // unauthorised, so it cannot even be identified well enough to be told "forbidden".
        mockMvc.perform(post("/api/auth/temporary/convert")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isUnauthorized());

        assertThat(userRepository.findById(session.get("session").get("userId").asLong())
                .orElseThrow().getRole()).isEqualTo(RoleName.TEMP_PASSENGER);
    }

    @Test
    @DisplayName("The reserved guest email domain cannot be registered")
    void reservedGuestDomainCannotBeRegistered() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("fullName", "Impostor Guest");
        body.put("email", "someone@guest.yatrarail.invalid");
        body.put("phone", "9876543210");
        body.put("password", "Password@123");
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------
    // The headline flow: a guest books and pays, one time
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A guest can quote, book and pay without ever registering")
    void guestCanCompleteAOneTimeBooking() throws Exception {
        String token = createGuest("One Time Booker", null).get("token").asText();
        BookingFixture fixture = bookingFixture();

        Map<String, Object> quote = new HashMap<>();
        quote.put("trainId", fixture.trainId());
        quote.put("coachId", fixture.coachId());
        quote.put("sourceStationId", fixture.sourceId());
        quote.put("destinationStationId", fixture.destinationId());
        quote.put("journeyDate", fixture.journeyDate().toString());
        quote.put("passengers", 1);
        quote.put("allowRac", true);

        mockMvc.perform(post("/api/bookings/quote")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(quote)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalFare").isNumber());

        CreateBookingRequest create = new CreateBookingRequest();
        create.setTrainId(fixture.trainId());
        create.setCoachId(fixture.coachId());
        create.setSourceStationId(fixture.sourceId());
        create.setDestinationStationId(fixture.destinationId());
        create.setJourneyDate(fixture.journeyDate());
        create.setPassengers(List.of(passenger("One Time Booker")));
        create.setAllowRac(true);

        MvcResult created = mockMvc.perform(post("/api/bookings")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "guest-booking-" + n)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(create)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PAYMENT_PENDING"))
                .andReturn();

        long bookingId = objectMapper.readTree(created.getResponse().getContentAsString())
                .get("data").get("id").asLong();

        ProcessPaymentRequest payment = new ProcessPaymentRequest();
        payment.setMethod("UPI");
        payment.setSimulateSuccess(true);

        mockMvc.perform(post("/api/bookings/" + bookingId + "/payment")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "guest-payment-" + n)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payment)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.payment.status").value("SUCCESS"));

        mockMvc.perform(get("/api/bookings/" + bookingId).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"));
    }

    @Test
    @DisplayName("A guest cannot read or cancel another passenger's booking")
    void guestCannotTouchSomeoneElsesBooking() throws Exception {
        String ownerToken = registerMember("owner" + n + "@example.com", "Booking Owner");
        Long bookingId = guestBooksOneSeat(ownerToken, "Owner Passenger");

        String otherToken = createGuest("Nosy Guest", null).get("token").asText();

        mockMvc.perform(get("/api/bookings/" + bookingId).header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound());

        Map<String, Object> cancel = new HashMap<>();
        cancel.put("reason", "not mine");
        mockMvc.perform(post("/api/bookings/" + bookingId + "/cancel")
                        .header("Authorization", "Bearer " + otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(cancel)))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private JsonNode createGuest(String fullName, String phone) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("fullName", fullName);
        if (phone != null) {
            body.put("phone", phone);
        }
        MvcResult result = mockMvc.perform(post("/api/auth/temporary")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
    }

    private String registerMember(String email, String fullName) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("fullName", fullName);
        body.put("email", email);
        body.put("phone", "987" + String.format("%07d", Math.abs(email.hashCode() % 10000000L)));
        body.put("password", "Password@123");
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("data").get("token").asText();
    }

    private Long guestBooksOneSeat(String token, String passengerName) throws Exception {
        BookingFixture fixture = bookingFixture();
        CreateBookingRequest create = new CreateBookingRequest();
        create.setTrainId(fixture.trainId());
        create.setCoachId(fixture.coachId());
        create.setSourceStationId(fixture.sourceId());
        create.setDestinationStationId(fixture.destinationId());
        create.setJourneyDate(fixture.journeyDate());
        create.setPassengers(List.of(passenger(passengerName)));
        create.setAllowRac(true);

        MvcResult result = mockMvc.perform(post("/api/bookings")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "fixture-booking-" + n + "-" + System.nanoTime())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(create)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("data").get("id").asLong();
    }

    private record BookingFixture(Long trainId, Long coachId, Long sourceId, Long destinationId,
                                  LocalDate journeyDate) {
    }

    private PassengerDto passenger(String name) {        PassengerDto dto = new PassengerDto();
        dto.setFullName(name);
        dto.setAge(30);
        dto.setGender("MALE");
        dto.setBerthPreference("NO_PREFERENCE");
        return dto;
    }

    /**
     * A coach created for this test class alone, rather than reusing the seeded demo coach. Other
     * suites add and close coaches on the same seeded train, so looking one up by
     * (train, class) is not reproducible across the whole run.
     */
    private BookingFixture bookingFixture() {
        if (fixture == null) {
            Train train = trainRepository.findByTrainNumber("12301").orElseThrow();
            Coach coach = coachRepository.save(Coach.builder()
                    .train(train)
                    .coachCode(TEST_COACH_PREFIX + n)
                    .seatClass(SeatClass.THIRD_AC)
                    .totalSeats(40)
                    .baseFare(new java.math.BigDecimal("950"))
                    .racCapacity(4)
                    .active(true)
                    .build());
            for (int i = 1; i <= 40; i++) {
                seatRepository.save(Seat.builder()
                        .coach(coach)
                        .seatNumber(i)
                        .berthType(i % 2 == 0 ? "WINDOW" : "AISLE")
                        .active(true)
                        .build());
            }
            fixture = new BookingFixture(train.getId(), coach.getId(),
                    stationId("NDLS"), stationId("LKO"), LocalDate.now().plusDays(1));
        }
        return fixture;
    }

    private Long stationId(String code) {
        Station station = stationRepository.findByCode(code).orElseThrow();
        return station.getId();
    }

    private void backdateDeadline(Long userId, LocalDateTime deadline) {
        User user = userRepository.findById(userId).orElseThrow();
        user.setTempExpiresAt(deadline);
        userRepository.saveAndFlush(user);
    }

    /** Booking state is global, so it is wiped between tests rather than rolled back. */
    private void resetBookingState() {
        transactionTemplate.executeWithoutResult(status -> {
            cancellationRepository.deleteAll();
            paymentAttemptRepository.deleteAll();
            paymentRepository.deleteAll();
            seatReservationRepository.deleteAll();
            bookingRepository.deleteAll();
            coachRepository.findAll().stream()
                    .filter(c -> c.getCoachCode() != null && c.getCoachCode().startsWith(TEST_COACH_PREFIX))
                    .forEach(coach -> {
                        seatRepository.deleteByCoachId(coach.getId());
                        coachRepository.delete(coach);
                    });
            seatRepository.flush();
            coachRepository.flush();
        });
    }
}

package com.yatrarail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yatrarail.dto.auth.RegisterRequest;
import com.yatrarail.dto.booking.BookingResponseDto;
import com.yatrarail.dto.booking.CancelBookingRequest;
import com.yatrarail.dto.booking.CreateBookingRequest;
import com.yatrarail.dto.booking.PassengerDto;
import com.yatrarail.dto.booking.ProcessPaymentRequest;
import com.yatrarail.dto.payment.PassengerRefundRequest;
import com.yatrarail.dto.payment.RefundRecordDto;
import com.yatrarail.dto.ticket.TicketDto;
import com.yatrarail.dto.ticket.TicketVerificationResultDto;
import com.yatrarail.entity.*;
import com.yatrarail.exception.ResourceNotFoundException;
import com.yatrarail.repository.*;
import com.yatrarail.service.BookingService;
import com.yatrarail.service.CancellationService;
import com.yatrarail.service.TicketService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 5 — the printable ticket and the verification of a scanned QR code.
 * <p>
 * The behaviour under test is that <b>nothing on paper is believed</b>. Every case here either
 * forges, tampers with, re-dates or replays a payload, and the assertion is always that the live
 * booking — not the bitmap — decides the verdict.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TicketVerificationIntegrationTest {

    private static final AtomicInteger COUNTER = new AtomicInteger();

    /**
     * Phones are unique in the schema, and {@link #COUNTER} is also advanced by setUp and by every
     * generated passenger name - so deriving a phone from it let two registrations in the same run
     * collide and fail the test with a 409 that looked like a product bug.
     */
    private static final AtomicInteger PHONE_COUNTER = new AtomicInteger(1_000);

    @Autowired
    private TicketService ticketService;
    @Autowired
    private BookingService bookingService;
    @Autowired
    private CancellationService cancellationService;
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private BookingRepository bookingRepository;
    @Autowired
    private SeatReservationRepository seatReservationRepository;
    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private PaymentAttemptRepository paymentAttemptRepository;
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
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private TransactionTemplate transactionTemplate;

    private Long userId;
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
        userId = newUser("ticket" + n + "@example.com", "Ticket Holder", RoleName.PASSENGER);
        strangerId = newUser("gate" + n + "@example.com", "At The Gate", RoleName.PASSENGER);
        staffId = newUser("conductor" + n + "@example.com", "Conductor", RoleName.STAFF);

        rajdhani = trainRepository.findByTrainNumber("12301").orElseThrow();
        thirdAcCoach = coachRepository.findByTrainIdAndSeatClass(rajdhani.getId(), SeatClass.THIRD_AC)
                .orElseThrow();
        reactivateSeededThirdAcCoaches();
        ndls = stationId("NDLS");
        lko = stationId("LKO");
    }

    private void resetState() {
        transactionTemplate.executeWithoutResult(status -> {
            cancellationRepository.deleteAll();
            paymentAttemptRepository.deleteAll();
            paymentRepository.deleteAll();
            seatReservationRepository.deleteAll();
            bookingRepository.deleteAll();
            // The Phase 2-4 suites create their own coaches on prefixes ZQ/ZU/ZT and delete them in
            // their own setUp, which leaves the last one behind. Left in place, that coach would
            // make `findByTrainIdAndSeatClass(THIRD_AC)` return more than one row and fail this
            // suite in a way that has nothing to do with the code under test. The seeded codes
            // (A1, B1, B2, S1, C1) never start with Z, so the prefix is a safe test-only marker.
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
    // The printable ticket
    // ==================================================================

    @Nested
    @DisplayName("the printable ticket")
    class PrintableTicket {

        @Test
        @DisplayName("carries the journey, the passengers, the fare snapshot and a signed QR payload")
        void aTicketCarriesEverythingAGateNeeds() {
            BookingResponseDto booking = createAndPay(userId, LocalDate.now().plusDays(1), 2);

            TicketDto ticket = ticketService.getTicket(userId, booking.getId());

            assertThat(ticket.getBookingReference()).isEqualTo(booking.getBookingReference());
            assertThat(ticket.getTrainNumber()).isEqualTo("12301");
            assertThat(ticket.getSourceStationName()).isNotBlank();
            assertThat(ticket.getDestinationStationName()).isNotBlank();
            assertThat(ticket.getJourneyDate()).isEqualTo(booking.getJourneyDate());
            assertThat(ticket.getSeatClass()).isNotBlank();
            assertThat(ticket.getPassengers()).hasSize(2);
            assertThat(ticket.getPassengers()).allSatisfy(p -> {
                assertThat(p.getFullName()).isNotBlank();
                assertThat(p.getSeatNumber()).isNotNull();
                assertThat(p.getPassengerStatus()).isEqualTo("CONFIRMED");
            });
            assertThat(ticket.getFareBreakdown()).isNotNull();
            assertThat(ticket.getValidForTravel()).isTrue();
            assertThat(ticket.getQrPayload()).startsWith("YATRARAIL-TICKET.");
        }

        @Test
        @DisplayName("states the refund policy the backend will actually apply")
        void aTicketStatesTheRefundPolicy() {
            BookingResponseDto booking = createAndPay(userId, LocalDate.now().plusDays(10), 1);

            TicketDto ticket = ticketService.getTicket(userId, booking.getId());

            assertThat(ticket.getRefundPolicy()).hasSize(3);
            assertThat(ticket.getRefundPolicy())
                    .extracting("refundPercent")
                    .containsExactly(90, 50, 25);
            assertThat(ticket.getCancellable()).isTrue();
        }

        @Test
        @DisplayName("encodes no fare, no passenger name and no seat in the QR payload")
        void theQrPayloadLeaksNothing() {
            BookingResponseDto booking = createAndPay(userId, LocalDate.now().plusDays(1), 2);
            TicketDto ticket = ticketService.getTicket(userId, booking.getId());

            String payload = ticket.getQrPayload();
            assertThat(payload).contains(booking.getBookingReference());
            assertThat(payload).doesNotContain(
                    ticket.getPassengers().get(0).getFullName().split(" ")[0]);
            assertThat(payload).doesNotContain("12301");
            assertThat(payload).doesNotContain(ticket.getFareBreakdown().getTotalPayable().toPlainString());
            // Six fixed segments, so the shape is checked before any cryptography is attempted.
            assertThat(payload.split("\\.")).hasSize(6);
        }

        @Test
        @DisplayName("is not handed to a different passenger, who gets the same 404 as for a missing booking")
        void aTicketIsOwnerOnly() {
            BookingResponseDto booking = createAndPay(userId, LocalDate.now().plusDays(1), 1);

            assertThatThrownBy(() -> ticketService.getTicket(strangerId, booking.getId()))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessage("Booking not found");
            assertThatThrownBy(() -> ticketService.getTicket(strangerId, 999_999L))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessage("Booking not found");
        }

        @Test
        @DisplayName("is not handed to staff, who have their own verification endpoint")
        void staffDoNotGetSomebodyElsesTicket() {
            BookingResponseDto booking = createAndPay(userId, LocalDate.now().plusDays(1), 1);

            assertThatThrownBy(() -> ticketService.getTicket(staffId, booking.getId()))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("is issued but marked not valid for travel when the booking was never paid for")
        void anUnpaidBookingGetsAnInvalidTicket() {
            BookingResponseDto booking = create(userId, LocalDate.now().plusDays(1), 1);

            TicketDto ticket = ticketService.getTicket(userId, booking.getId());

            assertThat(ticket.getValidForTravel()).isFalse();
            assertThat(ticket.getValidityMessage()).contains("not paid for");
            assertThat(ticket.getCancellable()).isFalse();
        }
    }

    // ==================================================================
    // Verification outcomes
    // ==================================================================

    @Nested
    @DisplayName("verifying a scanned payload")
    class Verification {

        @Test
        @DisplayName("a genuine ticket on the day of travel is valid")
        void aGenuineTicketOnTheDayIsValid() {
            BookingResponseDto booking = createAndPay(userId, LocalDate.now(), 1);
            String payload = ticketService.getTicket(userId, booking.getId()).getQrPayload();

            TicketVerificationResultDto result = verify(userId, payload);

            assertThat(result.getOutcome()).isEqualTo(TicketVerificationResultDto.OUTCOME_VALID);
            assertThat(result.getValid()).isTrue();
            assertThat(result.getBookingId()).isEqualTo(booking.getId());
            assertThat(result.getBookingReference()).isEqualTo(booking.getBookingReference());
            assertThat(result.getTrainNumber()).isEqualTo("12301");
            assertThat(result.getPassengers()).hasSize(1);
            assertThat(result.getAmountPaid()).isPositive();
            assertThat(result.getRefundedAmount()).isEqualByComparingTo("0.00");
            assertThat(result.getVerifiedAt()).isNotNull();
        }

        @Test
        @DisplayName("a genuine ticket for another day is refused, whatever it says on paper")
        void aGenuineTicketOnTheWrongDayIsRefused() {
            BookingResponseDto booking = createAndPay(userId, LocalDate.now().plusDays(1), 1);
            String payload = ticketService.getTicket(userId, booking.getId()).getQrPayload();

            TicketVerificationResultDto result = verify(userId, payload);

            assertThat(result.getOutcome())
                    .isEqualTo(TicketVerificationResultDto.OUTCOME_NOT_JOURNEY_DAY);
            assertThat(result.getValid()).isFalse();
            assertThat(result.getMessage()).contains("not valid today");
        }

        @Test
        @DisplayName("a cancelled booking is reported cancelled even though the QR is genuine")
        void aCancelledBookingIsReportedCancelled() {
            BookingResponseDto booking = createAndPay(userId, LocalDate.now(), 1);
            // Printed while the ticket was still good, then cancelled at the gate.
            String payload = ticketService.getTicket(userId, booking.getId()).getQrPayload();
            CancelBookingRequest request = new CancelBookingRequest();
            request.setReason("printed too early");
            bookingService.cancelBooking(userId, booking.getId(), request);

            TicketVerificationResultDto result = verify(userId, payload);

            assertThat(result.getOutcome()).isEqualTo(TicketVerificationResultDto.OUTCOME_CANCELLED);
            assertThat(result.getValid()).isFalse();
            assertThat(result.getRefundedAmount()).isPositive();
            assertThat(result.getMessage()).contains("cancelled");
        }

        @Test
        @DisplayName("a booking that was never paid for is reported unpaid, not valid")
        void anUnpaidBookingIsReportedUnpaid() {
            BookingResponseDto booking = create(userId, LocalDate.now(), 1);
            String payload = ticketService.getTicket(userId, booking.getId()).getQrPayload();

            TicketVerificationResultDto result = verify(userId, payload);

            assertThat(result.getOutcome()).isEqualTo(TicketVerificationResultDto.OUTCOME_UNPAID);
            assertThat(result.getValid()).isFalse();
            assertThat(result.getAmountPaid()).isNull();
        }

        @Test
        @DisplayName("a partially-refunded booking reports the money already returned")
        void aPartiallyRefundedBookingReportsTheRefundedTotal() {
            BookingResponseDto booking = createAndPay(userId, LocalDate.now().plusDays(10), 2);
            String payload = ticketService.getTicket(userId, booking.getId()).getQrPayload();
            List<Long> passengerIds = passengerIds(booking.getId());

            var refunds = cancellationRefunds(booking.getId(), passengerIds.subList(0, 1));
            assertThat(refunds).hasSize(1);

            TicketVerificationResultDto result = verify(userId, payload);

            assertThat(result.getRefundedAmount()).isEqualByComparingTo(refunds.get(0).getRefundAmount());
        }

        @Test
        @DisplayName("an expired booking is reported expired")
        void anExpiredBookingIsReportedExpired() {
            BookingResponseDto booking = createAndPay(userId, LocalDate.now(), 1);
            String payload = ticketService.getTicket(userId, booking.getId()).getQrPayload();
            transactionTemplate.executeWithoutResult(status -> {
                Booking live = bookingRepository.findById(booking.getId()).orElseThrow();
                live.setStatus(BookingStatus.EXPIRED);
                bookingRepository.saveAndFlush(live);
            });

            TicketVerificationResultDto result = verify(userId, payload);

            assertThat(result.getOutcome()).isEqualTo(TicketVerificationResultDto.OUTCOME_EXPIRED);
            assertThat(result.getValid()).isFalse();
        }
    }

    // ==================================================================
    // Forgery and tampering
    // ==================================================================

    @Nested
    @DisplayName("a forged or tampered payload is rejected")
    class Forgery {

        @Test
        @DisplayName("flipping a character of the signature is caught")
        void aTamperedSignatureIsCaught() {
            BookingResponseDto booking = createAndPay(userId, LocalDate.now(), 1);
            String payload = ticketService.getTicket(userId, booking.getId()).getQrPayload();
            String[] parts = payload.split("\\.");
            char first = parts[5].charAt(0);
            parts[5] = (first == 'A' ? 'B' : 'A') + parts[5].substring(1);

            TicketVerificationResultDto result = verify(userId, String.join(".", parts));

            assertThat(result.getOutcome()).isEqualTo(TicketVerificationResultDto.OUTCOME_FORGED);
            assertThat(result.getValid()).isFalse();
            assertThat(result.getMessage()).contains("not issued by YatraRail");
        }

        @Test
        @DisplayName("substituting a different booking reference is caught, even a real one")
        void aSwappedReferenceIsCaught() {
            BookingResponseDto mine = createAndPay(userId, LocalDate.now(), 1);
            BookingResponseDto theirs = createAndPay(strangerId, LocalDate.now(), 1);
            String payload = ticketService.getTicket(userId, mine.getId()).getQrPayload();
            String[] parts = payload.split("\\.");
            parts[3] = theirs.getBookingReference();

            TicketVerificationResultDto result = verify(userId, String.join(".", parts));

            // The signature covers the reference, so moving to somebody else's real booking is a
            // forgery, not a lookup.
            assertThat(result.getOutcome()).isEqualTo(TicketVerificationResultDto.OUTCOME_FORGED);
            assertThat(result.getBookingReference()).isNull();
        }

        @Test
        @DisplayName("a correctly-signed reference that is not on the system is unknown, not forged")
        void aSignedButUnknownReferenceIsUnknown() {
            String payload = ticketService.sign("ZZZZZZZZ", LocalDateTime.now());

            TicketVerificationResultDto result = verify(userId, payload);

            assertThat(result.getOutcome())
                    .isEqualTo(TicketVerificationResultDto.OUTCOME_UNKNOWN_BOOKING);
            assertThat(result.getBookingReference()).isEqualTo("ZZZZZZZZ");
        }

        @Test
        @DisplayName("a payload dated in the future has not been issued yet")
        void aFutureDatedPayloadIsNotYetIssued() {
            BookingResponseDto booking = createAndPay(userId, LocalDate.now().plusDays(1), 1);
            String reference = booking.getBookingReference();
            String payload = ticketService.sign(reference, LocalDateTime.now().plusDays(2));

            TicketVerificationResultDto result = verify(userId, payload);

            assertThat(result.getOutcome())
                    .isEqualTo(TicketVerificationResultDto.OUTCOME_NOT_YET_ISSUED);
            assertThat(result.getBookingReference()).isEqualTo(reference);
        }

        @Test
        @DisplayName("a genuine but out-of-window print is told to reprint, not called a forgery")
        void aStalePrintIsReprintable() {
            BookingResponseDto booking = createAndPay(userId, LocalDate.now(), 1);
            // Properly signed by this deployment, just printed far too long ago.
            String payload = ticketService.sign(booking.getBookingReference(),
                    LocalDateTime.now().minusDays(400));

            TicketVerificationResultDto result = verify(userId, payload);

            assertThat(result.getOutcome()).isEqualTo(TicketVerificationResultDto.OUTCOME_STALE);
            assertThat(result.getValid()).isFalse();
            assertThat(result.getMessage()).contains("re-print");
        }

        @Test
        @DisplayName("a code that is not a YatraRail ticket is malformed, not forged")
        void aForeignCodeIsMalformed() {
            TicketVerificationResultDto result = verify(userId, "https://example.com/menu");

            assertThat(result.getOutcome())
                    .isEqualTo(TicketVerificationResultDto.OUTCOME_MALFORMED);
            assertThat(result.getValid()).isFalse();
            assertThat(result.getMessage()).isNotBlank();
        }

        @Test
        @DisplayName("every malformed shape is reported as MALFORMED rather than throwing")
        void everyMalformedShapeIsHandled() {
            List<String> rubbish = List.of(
                    "",
                    "   ",
                    "YATRARAIL-TICKET",
                    "YATRARAIL-TICKET.1.yatrarail.ABCD1234",
                    "SOMETHING-ELSE.1.yatrarail.ABCD1234.20000.signature",
                    "YATRARAIL-TICKET.1.staging.ABCD1234.20000.signature",
                    "YATRARAIL-TICKET.99.yatrarail.ABCD1234.20000.signature",
                    "YATRARAIL-TICKET.1.yatrarail.ABCD1234.notanumber.signature",
                    "YATRARAIL-TICKET.1.yatrarail..20000.signature",
                    "YATRARAIL-TICKET.1.yatrarail.ABCD1234.20000.");

            for (String payload : rubbish) {
                TicketVerificationResultDto result = verify(userId, payload);
                assertThat(result.getOutcome())
                        .withFailMessage("payload <%s> was not handled as malformed", payload)
                        .isEqualTo(TicketVerificationResultDto.OUTCOME_MALFORMED);
                assertThat(result.getValid()).isFalse();
            }
        }

        @Test
        @DisplayName("a null payload is malformed rather than a server error")
        void aNullPayloadIsMalformed() {
            TicketVerificationResultDto result = verify(userId, null);

            assertThat(result.getOutcome())
                    .isEqualTo(TicketVerificationResultDto.OUTCOME_MALFORMED);
        }

        @Test
        @DisplayName("a ticket from another YatraRail deployment is named as such")
        void anotherDeploymentIsExplained() {
            TicketVerificationResultDto result = verify(userId,
                    "YATRARAIL-TICKET.1.staging.ABCD1234.20000.c2ln");

            assertThat(result.getOutcome())
                    .isEqualTo(TicketVerificationResultDto.OUTCOME_MALFORMED);
            assertThat(result.getMessage()).contains("different YatraRail deployment");
        }
    }

    // ==================================================================
    // Who may verify
    // ==================================================================

    @Nested
    @DisplayName("verification authority")
    class Authority {

        @Test
        @DisplayName("a passenger cannot confirm that a stranger's ticket exists")
        void aPassengerCannotVerifyAStrangersTicket() {
            BookingResponseDto theirs = createAndPay(strangerId, LocalDate.now(), 1);
            String payload = ticketService.getTicket(strangerId, theirs.getId()).getQrPayload();

            TicketVerificationResultDto result = verify(userId, payload);

            // The same answer as for a reference that does not exist, so the endpoint cannot be used
            // to test whether a given reference is real.
            assertThat(result.getOutcome())
                    .isEqualTo(TicketVerificationResultDto.OUTCOME_UNKNOWN_BOOKING);
            assertThat(result.getMessage()).contains("not on the YatraRail system");
        }

        @Test
        @DisplayName("staff can verify any passenger's ticket")
        void staffCanVerifyAnyTicket() {
            BookingResponseDto theirs = createAndPay(strangerId, LocalDate.now(), 1);
            String payload = ticketService.getTicket(strangerId, theirs.getId()).getQrPayload();

            TicketVerificationResultDto result = verifyAsStaff(payload);

            assertThat(result.getOutcome()).isEqualTo(TicketVerificationResultDto.OUTCOME_VALID);
            assertThat(result.getBookingId()).isEqualTo(theirs.getId());
        }

        @Test
        @DisplayName("a passenger can verify their own ticket")
        void aPassengerCanVerifyTheirOwn() {
            BookingResponseDto booking = createAndPay(userId, LocalDate.now(), 1);
            String payload = ticketService.getTicket(userId, booking.getId()).getQrPayload();

            assertThat(verify(userId, payload).getValid()).isTrue();
        }
    }

    // ==================================================================
    // The HTTP surface
    // ==================================================================

    @Nested
    @DisplayName("the ticket endpoints")
    class Endpoints {

        @Test
        @DisplayName("the owner fetches their ticket over HTTP")
        void theOwnerFetchesTheirTicket() throws Exception {
            String email = "p5t-apiticket-" + COUNTER.incrementAndGet() + "@example.com";
            String token = register(email, "Api Ticket Holder");
            Long id = idOf(email);
            BookingResponseDto booking = createAndPay(id, LocalDate.now().plusDays(1), 1);

            mockMvc.perform(get("/api/tickets/bookings/" + booking.getId())
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.bookingReference")
                            .value(booking.getBookingReference()))
                    .andExpect(jsonPath("$.data.qrPayload").isNotEmpty())
                    .andExpect(jsonPath("$.data.passengers.length()").value(1));
        }

        @Test
        @DisplayName("another passenger is told the booking does not exist")
        void anotherPassengerGetsNotFound() throws Exception {
            String ownerEmail = "p5t-owner-" + COUNTER.incrementAndGet() + "@example.com";
            String ownerToken = register(ownerEmail, "Owner");
            String otherEmail = "p5t-other-" + COUNTER.incrementAndGet() + "@example.com";
            String otherToken = register(otherEmail, "Other");
            BookingResponseDto booking = createAndPay(idOf(ownerEmail), LocalDate.now().plusDays(1), 1);

            mockMvc.perform(get("/api/tickets/bookings/" + booking.getId())
                            .header("Authorization", "Bearer " + otherToken))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("an anonymous caller cannot read a ticket")
        void anAnonymousCallerIsUnauthorised() throws Exception {
            mockMvc.perform(get("/api/tickets/bookings/1"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.success").value(false));
        }

        @Test
        @DisplayName("verification answers 200 with a verdict, not a 4xx")
        void verificationAlwaysAnswersWithAVerdict() throws Exception {
            String token = register("p5t-verify-" + COUNTER.incrementAndGet() + "@example.com", "Verifier");

            mockMvc.perform(post("/api/tickets/verify")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"payload\":\"not-a-ticket\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.outcome")
                            .value(TicketVerificationResultDto.OUTCOME_MALFORMED))
                    .andExpect(jsonPath("$.data.valid").value(false));
        }

        @Test
        @DisplayName("a blank payload is a 400, because that is a broken request rather than a bad scan")
        void aBlankPayloadIsABrokenRequest() throws Exception {
            String token = register("p5t-blank-" + COUNTER.incrementAndGet() + "@example.com", "Blank");

            mockMvc.perform(post("/api/tickets/verify")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"payload\":\"\"}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("payment history and refunds are reachable by their owner only")
        void moneyEndpointsRequireAuthentication() throws Exception {
            mockMvc.perform(get("/api/payments")).andExpect(status().isUnauthorized());
            mockMvc.perform(get("/api/refunds")).andExpect(status().isUnauthorized());
            mockMvc.perform(get("/api/bookings/1/refund-preview"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("a passenger cannot reach the reconciliation report")
        void aPassengerCannotReadReconciliation() throws Exception {
            String token = register("p5t-recon-" + COUNTER.incrementAndGet() + "@example.com", "Nosy");

            mockMvc.perform(get("/api/admin/payments/reconciliation")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("an admin can read the reconciliation report")
        void anAdminCanReadReconciliation() throws Exception {
            // The seeded demo admin from the data seeder.
            String token = login("admin@yatrarail.demo", "Admin@123");

            mockMvc.perform(get("/api/admin/payments/reconciliation")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.reconciled").exists())
                    .andExpect(jsonPath("$.data.totalCollected").exists());
        }

        @Test
        @DisplayName("the refund preview quotes a figure over HTTP without changing anything")
        void thePreviewEndpointIsReadOnly() throws Exception {
            String email = "p5t-prev-" + COUNTER.incrementAndGet() + "@example.com";
            String token = register(email, "Previewer");
            Long id = idOf(email);
            BookingResponseDto booking = createAndPay(id, LocalDate.now().plusDays(10), 2);

            mockMvc.perform(get("/api/bookings/" + booking.getId() + "/refund-preview")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.refundPercent").value(90))
                    .andExpect(jsonPath("$.data.scope").value("FULL"))
                    .andExpect(jsonPath("$.data.lines.length()").value(2));

            assertThat(cancellationRepository.count()).isZero();
        }

        @Test
        @DisplayName("cancelling a subset over HTTP returns one refund record per passenger")
        void theCancelEndpointReturnsOneRecordPerPassenger() throws Exception {
            String email = "p5t-canc-" + COUNTER.incrementAndGet() + "@example.com";
            String token = register(email, "Canceller");
            Long id = idOf(email);
            BookingResponseDto booking = createAndPay(id, LocalDate.now().plusDays(10), 3);
            List<Long> passengerIds = passengerIds(booking.getId());

            mockMvc.perform(post("/api/bookings/" + booking.getId() + "/refunds")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(java.util.Map.of(
                                    "passengerIds", passengerIds.subList(0, 2),
                                    "reason", "one traveller fell through"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(2))
                    .andExpect(jsonPath("$.data[0].scope").value("PARTIAL"))
                    .andExpect(jsonPath("$.data[0].refundReference").isNotEmpty())
                    .andExpect(jsonPath("$.data[0].platformFeeRefunded").value(0.0));

            assertThat(cancellationRepository.findByBookingIdOrderByCancelledAtAsc(booking.getId()))
                    .hasSize(2);
        }

        @Test
        @DisplayName("cancelling a passenger who no longer exists is a 404, not a 500")
        void aForeignPassengerIdIsNotFound() throws Exception {
            String email = "p5t-ghost-" + COUNTER.incrementAndGet() + "@example.com";
            String token = register(email, "Ghost");
            Long id = idOf(email);
            BookingResponseDto booking = createAndPay(id, LocalDate.now().plusDays(10), 1);

            mockMvc.perform(post("/api/bookings/" + booking.getId() + "/refunds")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(java.util.Map.of(
                                    "passengerIds", List.of(999_999L),
                                    "reason", "stale id"))))
                    .andExpect(status().isNotFound());
        }
    }

    // ==================================================================
    // Helpers
    // ==================================================================

    private TicketVerificationResultDto verify(Long callerId, String payload) {
        return ticketService.verify(callerId, false, payload);
    }

    /** What a conductor sees: the same check, with the authority to read any booking. */
    private TicketVerificationResultDto verifyAsStaff(String payload) {
        return ticketService.verify(staffId, true, payload);
    }

    private List<RefundRecordDto> cancellationRefunds(Long bookingId, List<Long> ids) {
        PassengerRefundRequest request = new PassengerRefundRequest();
        request.setPassengerIds(ids);
        request.setReason("Phase 5 ticket test");
        return cancellationService.cancelPassengers(callerOf(bookingId), bookingId, request);
    }

    private Long callerOf(Long bookingId) {
        return transactionTemplate.execute(status ->
                bookingRepository.findById(bookingId).orElseThrow().getUser().getId());
    }

    private List<Long> passengerIds(Long bookingId) {
        return transactionTemplate.execute(status -> bookingRepository.findById(bookingId).orElseThrow()
                .getPassengers().stream()
                .map(BookingPassenger::getId)
                .sorted()
                .toList());
    }

    private BookingResponseDto create(Long uid, LocalDate journeyDate, int passengers) {
        return bookingService.createBooking(uid, request(journeyDate, passengers), null);
    }

    private BookingResponseDto createAndPay(Long uid, LocalDate journeyDate, int passengers) {
        BookingResponseDto dto = create(uid, journeyDate, passengers);
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
        thirdAcCoach = coachRepository.findByTrainIdAndSeatClass(rajdhani.getId(), SeatClass.THIRD_AC)
                .orElseThrow();
    }

    private String register(String email, String fullName) throws Exception {
        RegisterRequest request = new RegisterRequest();
        request.setFullName(fullName);
        request.setEmail(email);
        request.setPhone("98" + String.format("%08d", PHONE_COUNTER.incrementAndGet()));
        request.setPassword("Password@123");
        var result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        return json.path("data").path("token").asText();
    }

    private String login(String email, String password) throws Exception {
        var result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                java.util.Map.of("email", email, "password", password))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        return json.path("data").path("token").asText();
    }

    private Long idOf(String email) {
        return userRepository.findByEmail(email).orElseThrow().getId();
    }

    private Long newUser(String email, String fullName, RoleName role) {
        return transactionTemplate.execute(status -> userRepository.saveAndFlush(User.builder()
                .fullName(fullName)
                .email(email)
                .password(passwordEncoder.encode("Passw0rd!"))
                .role(role)
                .active(true)
                .temporaryAccount(false)
                .build()).getId());
    }

    private Long stationId(String code) {
        return stationRepository.findAll().stream()
                .filter(s -> code.equals(s.getCode()))
                .findFirst()
                .orElseThrow()
                .getId();
    }
}

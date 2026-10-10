package com.yatrarail;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yatrarail.dto.booking.BookingResponseDto;
import com.yatrarail.dto.booking.CancelBookingRequest;
import com.yatrarail.dto.booking.CreateBookingRequest;
import com.yatrarail.dto.booking.PassengerDto;
import com.yatrarail.dto.booking.ProcessPaymentRequest;
import com.yatrarail.dto.notification.NotificationDto;
import com.yatrarail.dto.notification.UpdateNotificationPreferenceRequest;
import com.yatrarail.entity.*;
import com.yatrarail.exception.ResourceNotFoundException;
import com.yatrarail.repository.*;
import com.yatrarail.service.BookingService;
import com.yatrarail.service.NotificationPreferenceService;
import com.yatrarail.service.NotificationService;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 9 notification system, against a real (in-memory) database.
 * <p>
 * Two claims are under test and both are things the old code got wrong:
 * <ol>
 *   <li><b>A broadcast cannot own a read flag.</b> One person opening an announcement must not mark
 *       it read for everybody else, and it must appear in the badge until <i>that</i> person has
 *       opened it.</li>
 *   <li><b>A user can turn down the noise but not the facts.</b> Every preference is testable in both
 *       directions, and the one invariant that matters is that no combination of settings can hide a
 *       payment, an expiry, a cancellation or a promotion.</li>
 * </ol>
 * The waitlist tests close a throwaway coach so promotion and queue movement are decided by a known,
 * tiny inventory rather than by the 64-seat demo coach.
 * <p>
 * Deliberately NOT wrapped in a test-wide transaction: each service call gets its own transaction,
 * which is how it behaves in production. State is reset in {@link #setUp()} instead.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NotificationSystemIntegrationTest {

    private static final AtomicInteger COUNTER = new AtomicInteger();
    private static final AtomicInteger PHONE_COUNTER = new AtomicInteger(7_000);

    /** Coach codes for coaches this test creates for itself; torn down after every test. */
    private static final String TEST_COACH_PREFIX = "ZN";

    private static final String ADMIN_EMAIL = "admin@yatrarail.demo";
    private static final String ADMIN_PASSWORD = "Admin@123";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private NotificationService notificationService;
    @Autowired
    private NotificationPreferenceService preferenceService;
    @Autowired
    private BookingService bookingService;
    @Autowired
    private NotificationRepository notificationRepository;
    @Autowired
    private NotificationReadRepository notificationReadRepository;
    @Autowired
    private NotificationPreferenceRepository notificationPreferenceRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private CoachRepository coachRepository;
    @Autowired
    private SeatRepository seatRepository;
    @Autowired
    private TrainRepository trainRepository;
    @Autowired
    private StationRepository stationRepository;
    @Autowired
    private CancellationRepository cancellationRepository;
    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private PaymentAttemptRepository paymentAttemptRepository;
    @Autowired
    private SeatReservationRepository seatReservationRepository;
    @Autowired
    private BookingRepository bookingRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private TransactionTemplate transactionTemplate;

    private Long aliceId;
    private Long bobId;
    private Train rajdhani;
    private Long ndls;
    private Long pnbe;
    private LocalDate journeyDate;

    @BeforeEach
    void setUp() {
        // Bookings and notifications are global; wipe them so each test starts empty. The user table
        // is NOT wiped -- the seeded demo accounts and other test classes' users stay, because
        // notification reach is measured against the whole active non-temporary population.
        transactionTemplate.executeWithoutResult(status -> {
            notificationReadRepository.deleteAll();
            notificationPreferenceRepository.deleteAll();
            notificationRepository.deleteAll();
            cancellationRepository.deleteAll();
            paymentAttemptRepository.deleteAll();
            paymentRepository.deleteAll();
            seatReservationRepository.deleteAll();
            bookingRepository.deleteAll();
            coachRepository.findAll().stream()
                    .filter(c -> c.getCoachCode() != null && c.getCoachCode().startsWith(TEST_COACH_PREFIX))
                    .collect(Collectors.toList())
                    .forEach(coach -> {
                        seatRepository.deleteByCoachId(coach.getId());
                        coachRepository.delete(coach);
                    });
            seatRepository.flush();
            coachRepository.flush();
        });

        int n = COUNTER.incrementAndGet();
        aliceId = newUser("phase9-alice-" + n + "@example.com", "Alice Phase9");
        bobId = newUser("phase9-bob-" + n + "@example.com", "Bob Phase9");

        rajdhani = trainRepository.findByTrainNumber("12301").orElseThrow();
        ndls = stationRepository.findByCode("NDLS").orElseThrow().getId();
        pnbe = stationRepository.findByCode("PNBE").orElseThrow().getId();
        journeyDate = LocalDate.now().plusDays(1);
    }

    // ==================================================================
    // Broadcasts: read state belongs to the reader, not the notice
    // ==================================================================

    @Nested
    @DisplayName("Announcement read tracking")
    class AnnouncementReadTracking {

        @Test
        @DisplayName("one reader acknowledging an announcement leaves it unread for everyone else")
        void readingAnAnnouncementDoesNotMarkItReadForOthers() {
            NotificationDto announced = notificationService.announce("Service notice", "Platform 3 only.", adminId());
            Long id = announced.getId();

            notificationService.markAsRead(id, aliceId);

            // The whole defect in one assertion: before Phase 9 this flipped the shared is_read flag
            // and Bob's inbox claimed he had read a notice he had never opened.
            assertThat(readFlagFor(id)).as("the shared row carries no reader's state").isTrue();
            assertThat(notificationService.unreadCount(bobId)).isPositive();
            assertThat(feedFor(bobId))
                    .filteredOn(n -> id.equals(n.getId()))
                    .singleElement()
                    .extracting(NotificationDto::getIsRead)
                    .isEqualTo(false);
        }

        @Test
        @DisplayName("an announcement counts towards the badge until this reader opens it")
        void unreadAnnouncementsAreCounted() {
            NotificationDto announced = notificationService.announce("Delay notice", "Platform 3 only.", adminId());
            long before = notificationService.unreadCount(aliceId);

            assertThat(before)
                    .as("announcements used to be invisible to this count entirely")
                    .isEqualTo(1);
            assertThat(feedFor(aliceId))
                    .filteredOn(n -> announced.getId().equals(n.getId()))
                    .singleElement()
                    .extracting(NotificationDto::getIsRead)
                    .isEqualTo(false);

            notificationService.markAsRead(announced.getId(), aliceId);

            assertThat(notificationService.unreadCount(aliceId)).isZero();
        }

        @Test
        @DisplayName("acknowledging the same announcement twice records one receipt")
        void acknowledgingTwiceIsIdempotent() {
            NotificationDto announced = notificationService.announce("Repeatable", "Click me twice.", adminId());

            notificationService.markAsRead(announced.getId(), aliceId);
            notificationService.markAsRead(announced.getId(), aliceId);

            // The unique (notification, user) constraint is what makes this safe; without it a
            // double-click would inflate an administrator's reader count.
            assertThat(notificationReadRepository.countByNotificationId(announced.getId())).isEqualTo(1);
        }

        @Test
        @DisplayName("an announcement is still one row however many people have read it")
        void anAnnouncementIsNotCopiedPerReader() {
            NotificationDto announced = notificationService.announce("One notice", "For everybody.", adminId());

            notificationService.markAsRead(announced.getId(), aliceId);
            notificationService.markAsRead(announced.getId(), bobId);

            assertThat(notificationRepository.findByUserIsNullOrderByCreatedAtDesc())
                    .filteredOn(n -> announced.getId().equals(n.getId()))
                    .hasSize(1);
            assertThat(notificationReadRepository.countByNotificationId(announced.getId())).isEqualTo(2);
        }

        @Test
        @DisplayName("'mark all read' clears announcements too, and the badge stays clear")
        void markAllReadIncludesAnnouncements() {
            notificationService.announce("First", "One.", adminId());
            notificationService.announce("Second", "Two.", adminId());
            User alice = userRepository.findById(aliceId).orElseThrow();
            notificationService.notify(alice, NotificationType.BOOKING_CONFIRMATION, "Booking", "Personal.");

            assertThat(notificationService.unreadCount(aliceId)).isPositive();

            notificationService.markAllAsRead(aliceId);

            assertThat(notificationService.unreadCount(aliceId))
                    .as("the old implementation only touched personal rows, so the badge returned")
                    .isZero();
            assertThat(feedFor(aliceId)).allMatch(NotificationDto::getIsRead);
        }

        @Test
        @DisplayName("a personal notification still cannot be read by anybody but its owner")
        void personalNotificationsStayPrivate() {
            User alice = userRepository.findById(aliceId).orElseThrow();
            Long personalId = notificationService
                    .notify(alice, NotificationType.PAYMENT_STATUS, "Payment failed", "Try again.")
                    .orElseThrow().getId();

            notificationService.markAllAsRead(bobId);

            assertThat(feedFor(bobId)).noneMatch(n -> personalId.equals(n.getId()));
            // And the receipt model is not a loophole onto somebody else's inbox.
            assertThatThrownBy(() -> notificationService.markAsRead(personalId, bobId))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    // ==================================================================
    // Reach: the question an administrator actually has after publishing
    // ==================================================================

    @Nested
    @DisplayName("Announcement reach")
    class AnnouncementReach {

        @Test
        @DisplayName("an admin sees how many accounts a notice reached and how many read it")
        void reachIsReportable() throws Exception {
            NotificationDto announced = notificationService.announce("Reach test", "Who read this?", adminId());
            notificationService.markAsRead(announced.getId(), aliceId);

            mockMvc.perform(get("/api/admin/announcements")
                            .header("Authorization", "Bearer " + adminToken()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].id").value(announced.getId()))
                    .andExpect(jsonPath("$.data[0].audience").isNumber())
                    .andExpect(jsonPath("$.data[0].readCount").value(1));
        }

        @Test
        @DisplayName("a passenger cannot see announcement reach")
        void reachIsAdminOnly() throws Exception {
            mockMvc.perform(get("/api/admin/announcements")
                            .header("Authorization", "Bearer " + tokenFor(aliceId)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("publishing returns a DTO, not the entity")
        void publishingReturnsADto() throws Exception {
            String body = objectMapper.writeValueAsString(
                    Map.of("title", "Typed", "message", "No entity should leak here."));

            mockMvc.perform(post("/api/admin/announcements")
                            .header("Authorization", "Bearer " + adminToken())
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").isNumber())
                    .andExpect(jsonPath("$.data.title").value("Typed"))
                    // An exposed entity means the next column added to the table silently becomes
                    // part of the API; user is the field that proves this stayed a DTO.
                    .andExpect(jsonPath("$.data.user").doesNotExist());
        }
    }

    // ==================================================================
    // Preferences
    // ==================================================================

    @Nested
    @DisplayName("Notification preferences")
    class Preferences {

        @Test
        @DisplayName("an untouched account is served the defaults without a row being written")
        void readingPreferencesHasNoWriteSideEffect() throws Exception {
            assertThat(notificationPreferenceRepository.findByUserId(aliceId)).isEmpty();

            mockMvc.perform(get("/api/notifications/preferences")
                            .header("Authorization", "Bearer " + tokenFor(aliceId)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.announcementsEnabled").value(true))
                    .andExpect(jsonPath("$.data.waitlistAlerts").value("ALL"))
                    .andExpect(jsonPath("$.data.muted").value(false));

            assertThat(notificationPreferenceRepository.findByUserId(aliceId))
                    .as("reading settings must not create them")
                    .isEmpty();
        }

        @Test
        @DisplayName("preferences are the caller's own and nobody else's")
        void preferencesAreScopedToTheCaller() throws Exception {
            updatePrefs(aliceId, prefsBody(false, "OFF", null));

            mockMvc.perform(get("/api/notifications/preferences")
                            .header("Authorization", "Bearer " + tokenFor(bobId)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.announcementsEnabled").value(true))
                    .andExpect(jsonPath("$.data.waitlistAlerts").value("ALL"));
        }

        @Test
        @DisplayName("announcements turned off leave the feed and the badge alone")
        void turningAnnouncementsOffRemovesThem() throws Exception {
            updatePrefs(aliceId, prefsBody(false, "ALL", null));
            notificationService.announce("Quiet", "Should not reach Alice.", adminId());

            // Gating only the write would leave every notice ever published in the feed and the
            // badge forever, so "off" would still mean being interrupted about all the old ones.
            assertThat(feedFor(aliceId)).noneMatch(n -> "Quiet".equals(n.getTitle()));
            assertThat(notificationService.unreadCount(aliceId)).isZero();

            // Bob, who never opted out, still gets it: this is a personal setting, not a broadcast.
            assertThat(feedFor(bobId)).anyMatch(n -> "Quiet".equals(n.getTitle()));
        }

        @Test
        @DisplayName("turning announcements back on brings the backlog, still unread")
        void turningThemBackOnRestoresThem() throws Exception {
            updatePrefs(aliceId, prefsBody(false, "ALL", null));
            notificationService.announce("Missed", "While muted.", adminId());
            assertThat(feedFor(aliceId)).noneMatch(n -> "Missed".equals(n.getTitle()));

            updatePrefs(aliceId, prefsBody(true, "ALL", null));

            assertThat(feedFor(aliceId))
                    .filteredOn(n -> "Missed".equals(n.getTitle()))
                    .singleElement()
                    .extracting(NotificationDto::getIsRead)
                    .as("suppressed must not also mean secretly acknowledged")
                    .isEqualTo(false);
        }

        @Test
        @DisplayName("a mute silences announcements and expires on its own")
        void aMuteExpiresByItself() throws Exception {
            updatePrefs(aliceId, prefsBody(true, "ALL", LocalDateTime.now().plusHours(3).withNano(0)));
            notificationService.announce("Muted notice", "Shhh.", adminId());

            assertThat(feedFor(aliceId)).noneMatch(n -> "Muted notice".equals(n.getTitle()));

            // A deadline rather than a switch: nobody has to remember to un-mute.
            transactionTemplate.executeWithoutResult(status -> {
                NotificationPreference preference = notificationPreferenceRepository.findByUserId(aliceId)
                        .orElseThrow();
                preference.setMutedUntil(LocalDateTime.now().minusMinutes(1));
                notificationPreferenceRepository.save(preference);
            });

            assertThat(feedFor(aliceId)).anyMatch(n -> "Muted notice".equals(n.getTitle()));
        }

        @Test
        @DisplayName("a mute that has already ended is refused rather than stored")
        void aPastMuteIsRejected() throws Exception {
            mockMvc.perform(put("/api/notifications/preferences")
                            .header("Authorization", "Bearer " + tokenFor(aliceId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "announcementsEnabled", true,
                                    "waitlistAlerts", "ALL",
                                    "mutedUntil", LocalDateTime.now().minusDays(1)))))
                    .andExpect(status().isBadRequest());

            assertThat(notificationPreferenceRepository.findByUserId(aliceId))
                    .as("a mute nobody can act on must not be saved")
                    .isEmpty();
        }

        @Test
        @DisplayName("an unknown waitlist mode is rejected by name")
        void anUnknownWaitlistModeIsRejected() throws Exception {
            mockMvc.perform(put("/api/notifications/preferences")
                            .header("Authorization", "Bearer " + tokenFor(aliceId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"announcementsEnabled\":true,\"waitlistAlerts\":\"LOUD\"}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("preferences are not readable or writable anonymously")
        void preferencesRequireAuthentication() throws Exception {
            mockMvc.perform(get("/api/notifications/preferences")).andExpect(status().isUnauthorized());
            mockMvc.perform(put("/api/notifications/preferences")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"announcementsEnabled\":false,\"waitlistAlerts\":\"OFF\"}"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("the settings screen says which categories cannot be turned off")
        void alwaysDeliveredCategoriesAreDisclosed() throws Exception {
            mockMvc.perform(get("/api/notifications/preferences")
                            .header("Authorization", "Bearer " + tokenFor(aliceId)))
                    .andExpect(status().isOk())
                    // Derived from the enum rather than hand-listed, so this cannot drift out of
                    // date when a new always-on type is added.
                    .andExpect(jsonPath("$.data.alwaysDelivered[?(@.category=='Booking updates')]").exists())
                    .andExpect(jsonPath("$.data.alwaysDelivered[?(@.category=='Payment updates')]").exists())
                    .andExpect(jsonPath("$.data.alwaysDelivered[?(@.category=='Cancellation updates')]").exists())
                    .andExpect(jsonPath("$.data.waitlistOptions.length()").value(3));
        }
    }

    // ==================================================================
    // The invariant that makes preferences safe to ship
    // ==================================================================

    @Nested
    @DisplayName("Suppressible vs always-delivered")
    class SuppressionBoundaries {

        @Test
        @DisplayName("no setting can hide a payment, an expiry, a cancellation or a promotion")
        void nothingLoadBearingIsSuppressible() {
            // Every preference turned as far as it goes, including an all-day mute.
            User alice = userRepository.findById(aliceId).orElseThrow();
            preferenceService.update(aliceId, updateRequest(false, "OFF",
                    LocalDateTime.now().plusYears(1)));

            for (NotificationType type : NotificationType.values()) {
                assertThat(type.isSuppressible() || notificationService.notify(alice, type, "t", "m").isPresent())
                        .as("%s must be delivered whatever the recipient asked for", type)
                        .isTrue();
            }

            assertThat(notificationRepository.findByUserIdOrderByCreatedAtDesc(aliceId))
                    .extracting(n -> n.getType())
                    .contains(NotificationType.BOOKING_CONFIRMATION.name(),
                            NotificationType.PAYMENT_STATUS.name(),
                            NotificationType.BOOKING_EXPIRED.name(),
                            NotificationType.WAITLIST_UPDATE.name());
        }

        @Test
        @DisplayName("a staff alert is delivered even to a muted staff member")
        void staffAlertsSurviveAMute() {
            Long staffId = newUser("phase9-muted-staff-" + System.nanoTime() + "@example.com",
                    "Muted Staff", RoleName.STAFF);
            preferenceService.update(staffId, updateRequest(false, "OFF",
                    LocalDateTime.now().plusYears(1)));

            int notified = notificationService.notifyStaff(NotificationType.STAFF_INCIDENT,
                    "Coach fault", "Rake 4 reported a hot axle.");

            assertThat(notified).isPositive();
            assertThat(notificationRepository.findByUserIdOrderByCreatedAtDesc(staffId))
                    .extracting(n -> n.getType())
                    .contains(NotificationType.STAFF_INCIDENT.name());
        }

        @Test
        @DisplayName("'position changes only' drops the join notice but keeps the good news")
        void promotedOnlyKeepsMovement() {
            User alice = userRepository.findById(aliceId).orElseThrow();
            preferenceService.update(aliceId, updateRequest(true, "PROMOTED_ONLY", null));

            assertThat(notificationService.notify(alice, NotificationType.WAITLIST_JOINED,
                    "You are on the list", "...")).as("the opening line of the conversation").isEmpty();
            assertThat(notificationService.notify(alice, NotificationType.WAITLIST_MOVED,
                    "You have moved up", "...")).as("the part they asked to hear").isPresent();
            assertThat(notificationService.notify(alice, NotificationType.WAITLIST_MOVED,
                    "You have moved up", "again")).as("moves, not messages").isPresent();
        }

        @Test
        @DisplayName("waitlist alerts off silences both the join and the move")
        void waitlistAlertsOffSilencesTheQueue() {
            User alice = userRepository.findById(aliceId).orElseThrow();
            preferenceService.update(aliceId, updateRequest(true, "OFF", null));

            assertThat(notificationService.notify(alice, NotificationType.WAITLIST_JOINED, "t", "m")).isEmpty();
            assertThat(notificationService.notify(alice, NotificationType.WAITLIST_MOVED, "t", "m")).isEmpty();
            // The terminal update is not a queue preference at all: it says they have a seat.
            assertThat(notificationService.notify(alice, NotificationType.WAITLIST_UPDATE, "t", "m")).isPresent();
        }
    }

    // ==================================================================
    // The waitlist queue itself
    // ==================================================================

    @Nested
    @DisplayName("Waitlist movement")
    class WaitlistMovement {

        @Test
        @DisplayName("joining the queue tells you where you actually are")
        void joiningNotifiesTheRank() {
            Coach coach = newTestCoach(3, 0);
            fillAndPay(coach);

            Long first = scratchUser();
            Long second = scratchUser();
            BookingResponseDto wait1 = createAndPay(first, request(coach, 1));
            BookingResponseDto wait2 = createAndPay(second, request(coach, 1));

            assertThat(typesFor(first)).contains(NotificationType.WAITLIST_JOINED.name());
            assertThat(wait1.getPassengers().get(0).getWaitlistPosition())
                    .isLessThan(wait2.getPassengers().get(0).getWaitlistPosition());

            Notification joined = latestOfType(first, NotificationType.WAITLIST_JOINED);
            assertThat(joined.getMessage())
                    .contains(String.valueOf(wait1.getPassengers().get(0).getWaitlistPosition()));
        }

        @Test
        @DisplayName("a freed seat promotes the passenger ahead and moves the one behind")
        void promotionMovesTheRestOfTheQueue() {
            Coach coach = newTestCoach(3, 0);
            List<Long> holders = fillAndPay(coach);

            Long front = scratchUser();
            Long behind = scratchUser();
            BookingResponseDto waitFront = createAndPay(front, request(coach, 1));
            BookingResponseDto waitBehind = createAndPay(behind, request(coach, 1));
            int rankBehindBefore = waitBehind.getPassengers().get(0).getWaitlistPosition();

            bookingService.cancelBooking(holders.get(0),
                    findBookingId(holders.get(0), BookingStatus.CONFIRMED), cancelRequest());

            // The passenger in front takes the seat; that is an outcome, not a queue preference.
            assertThat(typesFor(front)).contains(NotificationType.WAITLIST_UPDATE.name());
            assertThat(bookingService.getBookingDetails(front, waitFront.getId())
                    .getPassengers().get(0).getPassengerStatus()).isEqualTo("CONFIRMED");

            // The passenger behind genuinely improved, and is told the real movement.
            Notification moved = latestOfType(behind, NotificationType.WAITLIST_MOVED);
            assertThat(moved).as("3 -> 2 is worth interrupting someone for").isNotNull();
            assertThat(moved.getMessage()).contains(String.valueOf(rankBehindBefore));
            int rankBehindAfter = bookingService.getBookingDetails(behind, waitBehind.getId())
                    .getPassengers().get(0).getWaitlistPosition();
            assertThat(rankBehindAfter).isEqualTo(rankBehindBefore - 1);
            assertThat(moved.getMessage()).contains(String.valueOf(rankBehindAfter));
            assertThat(typesFor(behind))
                    .as("a promotion notice must not masquerade as a movement")
                    .doesNotContain(NotificationType.WAITLIST_UPDATE.name());
        }

        @Test
        @DisplayName("a queue change that does not move you is not announced as a move")
        void anUnchangedRankIsNotAnnounced() {
            Coach coach = newTestCoach(3, 0);
            fillAndPay(coach);

            Long front = scratchUser();
            Long behind = scratchUser();
            BookingResponseDto waitFront = createAndPay(front, request(coach, 1));
            BookingResponseDto waitBehind = createAndPay(behind, request(coach, 1));
            assertThat(waitBehind.getPassengers().get(0).getWaitlistPosition()).isEqualTo(2);

            // The passenger BEHIND gives up their place. That leaves the queue, so the passenger in
            // front is now alone at rank 1 -- exactly where they already were. A notifier that
            // announced movement on any queue change would tell them they had moved up to a position
            // they never held.
            bookingService.cancelBooking(behind, findBookingId(behind, BookingStatus.WAITLISTED), cancelRequest());

            assertThat(bookingService.getBookingDetails(front, waitFront.getId())
                    .getPassengers().get(0).getWaitlistPosition())
                    .isEqualTo(1);
            assertThat(typesFor(front))
                    .as("their rank never changed")
                    .doesNotContain(NotificationType.WAITLIST_MOVED.name());
            assertThat(typesFor(front)).contains(NotificationType.WAITLIST_JOINED.name());
        }

        @Test
        @DisplayName("a queue with a gap in it is renumbered to the true rank")
        void storedPositionsAreTheTrueRank() {
            Coach coach = newTestCoach(3, 0);
            List<Long> holders = fillAndPay(coach);

            Long first = scratchUser();
            Long second = scratchUser();
            Long third = scratchUser();
            createAndPay(first, request(coach, 1));
            createAndPay(second, request(coach, 1));
            BookingResponseDto waitThird = createAndPay(third, request(coach, 1));
            assertThat(waitThird.getPassengers().get(0).getWaitlistPosition()).isEqualTo(3);

            // The second in line gives up their booking entirely: it leaves the queue, so the third
            // is now second, not third. Before Phase 9 nothing renumbered anything, so the stored
            // position was just whatever had been handed out, in order.
            bookingService.cancelBooking(second, findBookingId(second, BookingStatus.WAITLISTED), cancelRequest());

            assertThat(bookingService.getBookingDetails(third, waitThird.getId())
                    .getPassengers().get(0).getWaitlistPosition())
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("a booking that never paid does not take a place in the queue")
        void unpaidBookingsAreNotInTheQueue() {
            Coach coach = newTestCoach(3, 0);
            fillAndPay(coach);

            Long unpaid = scratchUser();
            create(unpaid, request(coach, 1));
            Long paid = scratchUser();
            BookingResponseDto waitPaid = createAndPay(paid, request(coach, 1));

            assertThat(waitPaid.getPassengers().get(0).getWaitlistPosition())
                    .as("an unpaid booking has not earned a place")
                    .isEqualTo(1);
            assertThat(typesFor(unpaid))
                    .as("no join notice for a place they do not hold")
                    .doesNotContain(NotificationType.WAITLIST_JOINED.name());
        }
    }

    // ==================================================================
    // helpers
    // ==================================================================

    private void updatePrefs(Long userId, Map<String, Object> body) throws Exception {
        mockMvc.perform(put("/api/notifications/preferences")
                        .header("Authorization", "Bearer " + tokenFor(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk());
    }

    /**
     * A PUT body. Built as a mutable map rather than {@code Map.of} because {@code Map.of} rejects
     * null values, and "no mute" is represented by the key being absent.
     */
    private Map<String, Object> prefsBody(Boolean announcements, String mode, LocalDateTime mutedUntil) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("announcementsEnabled", announcements);
        body.put("waitlistAlerts", mode);
        if (mutedUntil != null) {
            body.put("mutedUntil", mutedUntil);
        }
        return body;
    }

    private UpdateNotificationPreferenceRequest updateRequest(boolean announcements, String mode,
                                                            LocalDateTime mutedUntil) {
        UpdateNotificationPreferenceRequest request = new UpdateNotificationPreferenceRequest();
        request.setAnnouncementsEnabled(announcements);
        request.setWaitlistAlerts(mode);
        request.setMutedUntil(mutedUntil);
        return request;
    }

    private List<NotificationDto> feedFor(Long userId) {
        return notificationService.getForUser(userId);
    }

    private List<String> typesFor(Long userId) {
        return notificationRepository.findByUserIdOrderByCreatedAtDesc(userId)
                .stream().map(n -> n.getType()).toList();
    }

    private Notification latestOfType(Long userId, NotificationType type) {
        return notificationRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .filter(n -> type.name().equals(n.getType()))
                .findFirst().orElse(null);
    }

    /** The shared row's own flag -- kept only to prove it carries no reader's state. */
    private Boolean readFlagFor(Long notificationId) {
        return notificationRepository.findById(notificationId).orElseThrow().getIsRead();
    }

    private Coach newTestCoach(int seats, int racCapacity) {
        Coach coach = coachRepository.save(Coach.builder()
                .train(rajdhani)
                .coachCode(TEST_COACH_PREFIX + COUNTER.incrementAndGet())
                .seatClass(SeatClass.THIRD_AC)
                .totalSeats(seats)
                .baseFare(new BigDecimal("950"))
                .racCapacity(racCapacity)
                .active(true)
                .build());
        for (int i = 1; i <= seats; i++) {
            seatRepository.save(Seat.builder()
                    .coach(coach)
                    .seatNumber(i)
                    .berthType(i % 2 == 0 ? "WINDOW" : "AISLE")
                    .active(true)
                    .build());
        }
        return coach;
    }

    /** Fills every seat with a separate, paid, one-passenger booking. */
    private List<Long> fillAndPay(Coach coach) {
        return IntStream.range(0, coach.getTotalSeats())
                .mapToObj(i -> scratchUser())
                .peek(uid -> createAndPay(uid, request(coach, 1)))
                .toList();
    }

    private CreateBookingRequest request(Coach coach, int passengers) {
        CreateBookingRequest request = new CreateBookingRequest();
        request.setTrainId(rajdhani.getId());
        request.setCoachId(coach.getId());
        request.setSourceStationId(ndls);
        request.setDestinationStationId(pnbe);
        request.setJourneyDate(journeyDate);
        request.setPassengers(IntStream.range(0, passengers)
                .mapToObj(i -> {
                    PassengerDto dto = new PassengerDto();
                    dto.setFullName("Passenger " + (char) ('A' + i));
                    dto.setAge(30);
                    dto.setGender("MALE");
                    dto.setBerthPreference("NO_PREFERENCE");
                    return dto;
                })
                .toList());
        return request;
    }

    private BookingResponseDto create(Long uid, CreateBookingRequest request) {
        return bookingService.createBooking(uid, request, null);
    }

    /** Books and immediately pays, which is the only way a passenger joins the promotion queue. */
    private BookingResponseDto createAndPay(Long uid, CreateBookingRequest request) {
        BookingResponseDto dto = bookingService.createBooking(uid, request, null);
        ProcessPaymentRequest payment = new ProcessPaymentRequest();
        payment.setMethod("UPI");
        payment.setSimulateSuccess(true);
        return bookingService.processPayment(uid, dto.getId(), payment, null);
    }

    private CancelBookingRequest cancelRequest() {
        CancelBookingRequest request = new CancelBookingRequest();
        request.setReason("Phase 9 notification test");
        return request;
    }

    private Long findBookingId(Long uid, BookingStatus status) {
        return bookingRepository.findByUserIdOrderByCreatedAtDesc(uid).stream()
                .filter(b -> b.getStatus() == status)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No " + status + " booking for user " + uid))
                .getId();
    }

    private Long scratchUser() {
        return newUser("phase9-scratch-" + System.nanoTime() + "-" + COUNTER.incrementAndGet() + "@example.com",
                "Scratch " + COUNTER.incrementAndGet());
    }

    private Long newUser(String email, String fullName) {
        return newUser(email, fullName, RoleName.PASSENGER);
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
                .build()).getId());
    }

    private Long adminId() {
        return userRepository.findByEmail(ADMIN_EMAIL).orElseThrow().getId();
    }

    private String adminToken() throws Exception {
        return login(ADMIN_EMAIL, ADMIN_PASSWORD);
    }

    private String tokenFor(Long id) throws Exception {
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
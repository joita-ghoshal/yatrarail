package com.yatrarail.service;

import com.yatrarail.entity.RouteStation;
import com.yatrarail.entity.Station;
import com.yatrarail.entity.Train;
import com.yatrarail.exception.BadRequestException;
import com.yatrarail.repository.RouteStationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TrainScheduleValidatorTest {

    private final RouteStationRepository routeStationRepository = mock(RouteStationRepository.class);
    private final TrainScheduleValidator validator = new TrainScheduleValidator(routeStationRepository);

    private final Station delhi = station("NDLS", "New Delhi");
    private final Station lucknow = station("LKO", "Lucknow Charbagh");
    private final Station patna = station("PNBE", "Patna Junction");
    private final Station howrah = station("HWH", "Howrah Junction");
    private final Station misconfigured = station("BRK", "Broken Stop");

    private Train rajdhani;

    @BeforeEach
    void setUp() {
        // Howrah Rajdhani: NDLS -> LKO -> PNBE -> HWH, daily.
        rajdhani = Train.builder()
                .trainNumber("12301")
                .name("Howrah Rajdhani Express")
                .sourceStation(delhi)
                .destinationStation(howrah)
                .runsOn("MON,TUE,WED,THU,FRI,SAT,SUN")
                .active(true)
                .build();
        rajdhani.setId(1L);

        Map<Long, RouteStation> route = new HashMap<>();
        route.put(delhi.getId(), onRoute(rajdhani, delhi, 1, 0));
        route.put(lucknow.getId(), onRoute(rajdhani, lucknow, 2, 445));
        route.put(patna.getId(), onRoute(rajdhani, patna, 3, 900));
        route.put(howrah.getId(), onRoute(rajdhani, howrah, 4, 1450));
        // Misconfigured stop: correctly ordered (sequence 2) but sitting *past* the terminus on the
        // distance axis, so any segment from it would be negative.
        route.put(misconfigured.getId(), onRoute(rajdhani, misconfigured, 2, 5000));
        when(routeStationRepository.findByTrainIdAndStationId(eq(1L), any(Long.class)))
                .thenAnswer(invocation -> Optional.ofNullable(route.get(invocation.<Long>getArgument(1))));
    }

    @Test
    void runsOnDayMatchesExactDayTokensNotSubstrings() {
        Train sundayOnly = Train.builder().trainNumber("99999").name("Sunday Service").runsOn("SUN").active(true).build();

        // The old implementation used String.contains, so a "SUN" service matched any date whose
        // day name merely contained the letters "sun".
        assertThat(validator.runsOnDay(rajdhani, LocalDate.of(2026, 9, 27))).isTrue();  // Sunday
        assertThat(validator.runsOnDay(sundayOnly, LocalDate.of(2026, 9, 27))).isTrue(); // Sunday
    }

    @Test
    void runsOnDayRejectsDaysTheTrainDoesNotOperate() {
        Train monWedFri = Train.builder()
                .trainNumber("11111").name("MWF Service").runsOn("MON,WED,FRI").active(true).build();

        // 2026-09-29 is a Tuesday: not in the list, and "TUE" is not a substring of "TUESDAY".
        assertThat(validator.runsOnDay(monWedFri, LocalDate.of(2026, 9, 29))).isFalse();
        assertThat(validator.runsOnDay(monWedFri, LocalDate.of(2026, 9, 28))).isTrue();  // Monday
    }

    @Test
    void blankRunsOnMeansTheTrainNeverOperates() {
        Train unconfigured = Train.builder().trainNumber("22222").name("No Schedule").runsOn("  ").active(true).build();

        assertThat(validator.dayCodes(unconfigured)).isEmpty();
        assertThat(validator.runsOnDay(unconfigured, LocalDate.now())).isFalse();
    }

    @Test
    void resolveSegmentReturnsTheDistanceOfTheBookedSubSegment() {
        TrainScheduleValidator.Segment segment = validator.resolveSegment(rajdhani, lucknow.getId(), patna.getId());

        assertThat(segment.distanceKm()).isEqualTo(900 - 445);
    }

    @Test
    void resolveSegmentAllowsTheFullRoute() {
        TrainScheduleValidator.Segment segment = validator.resolveSegment(rajdhani, delhi.getId(), howrah.getId());

        assertThat(segment.distanceKm()).isEqualTo(1450);
    }

    @Test
    void resolveSegmentRejectsStationsTheTrainDoesNotStopAt() {
        Station mumbai = station("BCT", "Mumbai Central");

        assertThatThrownBy(() -> validator.resolveSegment(rajdhani, mumbai.getId(), howrah.getId()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("does not stop at the chosen source station");
    }

    @Test
    void resolveSegmentRejectsStationsInTheWrongTravelOrder() {
        // Patna is after Lucknow, so a Patna -> Lucknow journey is not possible on this train.
        assertThatThrownBy(() -> validator.resolveSegment(rajdhani, patna.getId(), lucknow.getId()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("correct travel order");
    }

    @Test
    void resolveSegmentRejectsIdenticalSourceAndDestination() {
        assertThatThrownBy(() -> validator.resolveSegment(rajdhani, lucknow.getId(), lucknow.getId()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("cannot be the same");
    }

    @Test
    void resolveSegmentRejectsANonPositiveSegmentDistance() {
        assertThatThrownBy(() -> validator.resolveSegment(rajdhani, misconfigured.getId(), howrah.getId()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("not configured correctly");
    }

    @Test
    void validateJourneyDateRejectsThePast() {
        assertThatThrownBy(() -> validator.validateJourneyDate(LocalDate.now().minusDays(1)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("cannot be in the past");
    }

    @Test
    void validateJourneyDateRejectsDatesBeyondTheAdvanceWindow() {
        assertThatThrownBy(() -> validator.validateJourneyDate(
                LocalDate.now().plusDays(TrainScheduleValidator.MAX_ADVANCE_DAYS + 1)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("days in advance");
    }

    @Test
    void validateJourneyDateAcceptsTodayAndTheLastBookableDay() {
        assertThatCode(() -> validator.validateJourneyDate(LocalDate.now())).doesNotThrowAnyException();
        assertThatCode(() -> validator.validateJourneyDate(
                LocalDate.now().plusDays(TrainScheduleValidator.MAX_ADVANCE_DAYS))).doesNotThrowAnyException();
    }

    @Test
    void validateServiceDayRejectsADayTheTrainIsNotRunning() {
        Train monWed = Train.builder()
                .trainNumber("33333").name("Mon Wed Express").runsOn("MON,WED").active(true).build();

        // Find a Tuesday inside the booking window.
        LocalDate tuesday = nextDayOf(DayOfWeek.TUESDAY);

        assertThatThrownBy(() -> validator.validateServiceDay(monWed, tuesday))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("does not run on");
    }

    @Test
    void validateServiceDayRejectsAnInactiveTrain() {
        rajdhani.setActive(false);

        assertThatThrownBy(() -> validator.validateServiceDay(rajdhani, LocalDate.now()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("not currently in service");
    }

    @Test
    void totalDistanceKmFallsBackToZeroWhenTheRouteIsEmpty() {
        when(routeStationRepository.findTotalDistanceKm(eq(99L))).thenReturn(null);

        assertThat(validator.totalDistanceKm(99L)).isZero();
    }

    @Test
    void totalDistanceKmReadsTheConfiguredRouteLength() {
        when(routeStationRepository.findTotalDistanceKm(eq(1L))).thenReturn(1450);

        assertThat(validator.totalDistanceKm(1L)).isEqualTo(1450);
    }

    // ------------------------------------------------------------------ helpers

    /** The next date on or after tomorrow that falls on the given day of week. */
    private static LocalDate nextDayOf(DayOfWeek day) {
        LocalDate date = LocalDate.now().plusDays(1);
        while (date.getDayOfWeek() != day) {
            date = date.plusDays(1);
        }
        return date;
    }

    private Station station(String code, String name) {
        Station s = Station.builder().code(code).name(name).active(true).build();
        s.setId((long) Math.abs(code.hashCode()));
        return s;
    }

    private RouteStation onRoute(Train train, Station station, int sequence, int distanceFromSource) {
        return RouteStation.builder()
                .train(train)
                .station(station)
                .sequenceNumber(sequence)
                .distanceFromSourceKm(distanceFromSource)
                .build();
    }
}

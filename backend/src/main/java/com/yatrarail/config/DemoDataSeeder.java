package com.yatrarail.config;

import com.yatrarail.entity.*;
import com.yatrarail.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Seeds realistic, clearly-fictional Indian-railway-inspired demo data on first startup only
 * (skipped automatically if stations already exist). This data is for academic/demo purposes
 * and is not sourced from or representative of real Indian Railways schedules or fares.
 */
@Component
@RequiredArgsConstructor
public class DemoDataSeeder implements CommandLineRunner {

    private final StationRepository stationRepository;
    private final TrainRepository trainRepository;
    private final RouteStationRepository routeStationRepository;
    private final CoachRepository coachRepository;
    private final SeatRepository seatRepository;
    private final FareRuleRepository fareRuleRepository;
    private final QuotaFareRepository quotaFareRepository;
    private final QuotaConfigRepository quotaConfigRepository;
    private final UserRepository userRepository;
    private final NotificationRepository notificationRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${yatrarail.seed-demo-data:true}")
    private boolean seedEnabled;

    @Override
    @Transactional
    public void run(String... args) {
        if (!seedEnabled || stationRepository.count() > 0) {
            return;
        }

        Map<String, Station> stations = seedStations();
        seedFareRules();
        seedQuotaFares();
        seedUsers();
        seedTrains(stations);
        // After the trains, because a split only exists for a (train, class) that has a coach.
        seedQuotaConfigs();
        notificationRepository.save(Notification.builder()
                .user(null)
                .type("SYSTEM_ANNOUNCEMENT")
                .title("Welcome to YatraRail")
                .message("YatraRail is an academic/portfolio demo project inspired by Indian Railways. All schedules, fares and availability shown are demo data.")
                .isRead(false)
                .build());
    }

    private Map<String, Station> seedStations() {
        Object[][] data = {
                {"NDLS", "New Delhi", "New Delhi", "Delhi"},
                {"BCT", "Mumbai Central", "Mumbai", "Maharashtra"},
                {"HWH", "Howrah Junction", "Kolkata", "West Bengal"},
                {"MAS", "Chennai Central", "Chennai", "Tamil Nadu"},
                {"SBC", "Bengaluru City", "Bengaluru", "Karnataka"},
                {"PUNE", "Pune Junction", "Pune", "Maharashtra"},
                {"JP", "Jaipur Junction", "Jaipur", "Rajasthan"},
                {"ADI", "Ahmedabad Junction", "Ahmedabad", "Gujarat"},
                {"LKO", "Lucknow Charbagh", "Lucknow", "Uttar Pradesh"},
                {"PNBE", "Patna Junction", "Patna", "Bihar"},
                {"BBS", "Bhubaneswar", "Bhubaneswar", "Odisha"},
                {"NGP", "Nagpur Junction", "Nagpur", "Maharashtra"},
        };
        Map<String, Station> map = new HashMap<>();
        for (Object[] row : data) {
            Station s = Station.builder()
                    .code((String) row[0]).name((String) row[1]).city((String) row[2]).state((String) row[3])
                    .active(true).build();
            map.put((String) row[0], stationRepository.save(s));
        }
        return map;
    }

    /**
     * Phase 4: the platform fee and GST are part of the tariff, not an afterthought, so a fare
     * rule is now four numbers rather than two. The simulator's flat fee is per booking, and GST is
     * applied to the passenger fare subtotal (never to the platform fee).
     */
    private void seedFareRules() {
        saveRule(SeatClass.FIRST_AC, "4.50", "1200", "30.00", "5.00");
        saveRule(SeatClass.SECOND_AC, "3.00", "800", "30.00", "5.00");
        saveRule(SeatClass.THIRD_AC, "2.00", "500", "30.00", "5.00");
        saveRule(SeatClass.SLEEPER, "0.80", "150", "25.00", "5.00");
        saveRule(SeatClass.CHAIR_CAR, "1.50", "300", "20.00", "5.00");
        saveRule(SeatClass.SECOND_SEATING, "0.40", "60", "15.00", "5.00");
    }

    private void saveRule(SeatClass seatClass, String farePerKm, String minimumFare,
                          String platformFee, String gstPercent) {
        fareRuleRepository.save(FareRule.builder()
                .seatClass(seatClass)
                .farePerKm(new BigDecimal(farePerKm))
                .minimumFare(new BigDecimal(minimumFare))
                .platformFee(new BigDecimal(platformFee))
                .gstPercent(new BigDecimal(gstPercent))
                .build());
    }

    /**
     * Phase 4: the default concessions. Only the two quotas that get a real discount in this
     * simulation are seeded; every other (class, quota) pair defaults to 0%, which is a valid
     * configuration rather than a missing one.
     */
    private void seedQuotaFares() {
        for (SeatClass seatClass : SeatClass.values()) {
            saveQuotaFare(seatClass, QuotaType.SENIOR, 40, "Senior citizen concession (simulated)");
            saveQuotaFare(seatClass, QuotaType.DIVYANG, 50, "Divyang concession (simulated)");
        }
    }

    private void saveQuotaFare(SeatClass seatClass, QuotaType quota, int discountPercent, String note) {
        quotaFareRepository.save(QuotaFare.builder()
                .seatClass(seatClass)
                .quotaType(quota)
                .discountPercent(discountPercent)
                .note(note)
                .build());
    }

    /**
     * Phase 4: creates a zeroed split for every (train, class) that has an active coach.
     * <p>
     * Every reserved slice starts at zero, so nothing is carved out and the whole class stays in the
     * general quota. That is deliberate: seeding a non-zero split here would silently shrink the
     * general quota of every train and change the fares of bookings that already exist. Quotas are
     * switched on per train and class from the admin UI.
     */
    private void seedQuotaConfigs() {
        for (Object[] pair : quotaConfigRepository.findTrainClassPairsWithActiveCoaches()) {
            Long trainId = (Long) pair[0];
            SeatClass seatClass = (SeatClass) pair[1];
            if (quotaConfigRepository.existsByTrainIdAndSeatClass(trainId, seatClass)) {
                continue;
            }
            quotaConfigRepository.save(QuotaConfig.builder()
                    .train(trainRepository.findById(trainId).orElseThrow())
                    .seatClass(seatClass)
                    .tatkalSeats(0)
                    .seniorSeats(0)
                    .ladiesSeats(0)
                    .divyangSeats(0)
                    .staffSeats(0)
                    .tatkalWindowDays(1)
                    .active(true)
                    .build());
        }
    }

    private void seedUsers() {
        userRepository.save(User.builder()
                .fullName("Admin User").email("admin@yatrarail.demo").phone("9000000001")
                .password(passwordEncoder.encode("Admin@123")).role(RoleName.ADMIN).active(true).build());
        userRepository.save(User.builder()
                .fullName("Staff User").email("staff@yatrarail.demo").phone("9000000002")
                .password(passwordEncoder.encode("Staff@123")).role(RoleName.STAFF).active(true).build());
        userRepository.save(User.builder()
                .fullName("Demo Passenger").email("passenger@yatrarail.demo").phone("9000000003")
                .password(passwordEncoder.encode("Passenger@123")).role(RoleName.PASSENGER).age(29).gender("Female").active(true).build());
    }

    private void seedTrains(Map<String, Station> st) {
        seedTrain("12301", "Howrah Rajdhani Express", TrainType.RAJDHANI, st.get("NDLS"), st.get("HWH"),
                LocalTime.of(16, 55), LocalTime.of(10, 5), 17 * 60 + 10, "MON,TUE,WED,THU,FRI,SAT,SUN",
                new int[][]{{0, 0}, {5, 445}, {8, 900}, {12, 1450}}, new String[]{"NDLS", "LKO", "PNBE", "HWH"});

        seedTrain("12951", "Mumbai Rajdhani Express", TrainType.RAJDHANI, st.get("NDLS"), st.get("BCT"),
                LocalTime.of(16, 25), LocalTime.of(8, 15), 15 * 60 + 50, "MON,TUE,WED,THU,FRI,SAT,SUN",
                new int[][]{{0, 0}, {6, 620}, {12, 1384}}, new String[]{"NDLS", "ADI", "BCT"});

        seedTrain("12259", "Sealdah Duronto Express", TrainType.DURONTO, st.get("HWH"), st.get("NDLS"),
                LocalTime.of(8, 25), LocalTime.of(23, 55), 15 * 60 + 30, "MON,WED,FRI,SUN",
                new int[][]{{0, 0}, {8, 1450}}, new String[]{"HWH", "NDLS"});

        seedTrain("12621", "Tamil Nadu Express", TrainType.SUPERFAST, st.get("NDLS"), st.get("MAS"),
                LocalTime.of(22, 30), LocalTime.of(7, 15), 32 * 60 + 45, "MON,TUE,WED,THU,FRI,SAT,SUN",
                new int[][]{{0, 0}, {10, 1050}, {20, 2180}}, new String[]{"NDLS", "NGP", "MAS"});

        seedTrain("12007", "Shatabdi Express", TrainType.SHATABDI, st.get("MAS"), st.get("SBC"),
                LocalTime.of(6, 0), LocalTime.of(11, 0), 5 * 60, "MON,TUE,WED,THU,FRI,SAT",
                new int[][]{{0, 0}, {5, 362}}, new String[]{"MAS", "SBC"});

        seedTrain("12138", "Punjab Mail", TrainType.EXPRESS, st.get("PUNE"), st.get("JP"),
                LocalTime.of(21, 10), LocalTime.of(14, 40), 17 * 60 + 30, "TUE,THU,SAT,SUN",
                new int[][]{{0, 0}, {9, 780}, {14, 1120}}, new String[]{"PUNE", "ADI", "JP"});

        seedTrain("18409", "Bhubaneswar Passenger", TrainType.PASSENGER, st.get("BBS"), st.get("HWH"),
                LocalTime.of(6, 45), LocalTime.of(13, 20), 6 * 60 + 35, "MON,TUE,WED,THU,FRI,SAT,SUN",
                new int[][]{{0, 0}, {5, 440}}, new String[]{"BBS", "HWH"});
    }

    private void seedTrain(String number, String name, TrainType type, Station source, Station destination,
                            LocalTime departure, LocalTime arrival, int durationMinutes, String runsOn,
                            int[][] seqAndDistance, String[] stationCodesInOrder) {
        Train train = Train.builder()
                .trainNumber(number).name(name).type(type)
                .sourceStation(source).destinationStation(destination)
                .departureTime(departure).arrivalTime(arrival).durationMinutes(durationMinutes)
                .runsOn(runsOn).active(true).build();
        train = trainRepository.save(train);

        Map<String, Station> byCode = new HashMap<>();
        byCode.put(source.getCode(), source);
        byCode.put(destination.getCode(), destination);
        for (String code : stationCodesInOrder) {
            if (!byCode.containsKey(code)) {
                stationRepository.findByCode(code).ifPresent(s -> byCode.put(code, s));
            }
        }

        for (int i = 0; i < stationCodesInOrder.length; i++) {
            int distance = seqAndDistance[i][1];
            LocalTime arr = i == 0 ? null : departure.plusMinutes((long) (distance / 1.2));
            LocalTime dep = i == stationCodesInOrder.length - 1 ? null : arr != null ? arr.plusMinutes(5) : departure;
            routeStationRepository.save(RouteStation.builder()
                    .train(train)
                    .station(byCode.get(stationCodesInOrder[i]))
                    .sequenceNumber(i + 1)
                    .arrivalTime(arr)
                    .departureTime(dep)
                    .haltMinutes(i == 0 || i == stationCodesInOrder.length - 1 ? 0 : 5)
                    .distanceFromSourceKm(distance)
                    .build());
        }

        addCoach(train, "A1", SeatClass.FIRST_AC, 18, new BigDecimal("2500"));
        addCoach(train, "B1", SeatClass.SECOND_AC, 48, new BigDecimal("1500"));
        addCoach(train, "B2", SeatClass.THIRD_AC, 64, new BigDecimal("950"));
        if (type == TrainType.EXPRESS || type == TrainType.SUPERFAST || type == TrainType.PASSENGER || type == TrainType.DURONTO) {
            addCoach(train, "S1", SeatClass.SLEEPER, 72, new BigDecimal("400"));
        }
        if (type == TrainType.SHATABDI) {
            addCoach(train, "C1", SeatClass.CHAIR_CAR, 78, new BigDecimal("700"));
        }
    }

    private void addCoach(Train train, String code, SeatClass seatClass, int totalSeats, BigDecimal baseFare) {
        Coach coach = coachRepository.save(Coach.builder()
                .train(train).coachCode(code).seatClass(seatClass)
                .totalSeats(totalSeats).baseFare(baseFare)
                .racCapacity(SeatClass.CHAIR_CAR == seatClass || SeatClass.SECOND_SEATING == seatClass ? 0 : 2)
                .active(true).build());
        for (int i = 1; i <= totalSeats; i++) {
            String berthType;
            if (seatClass == SeatClass.CHAIR_CAR || seatClass == SeatClass.SECOND_SEATING) {
                berthType = i % 2 == 0 ? "AISLE" : "WINDOW";
            } else {
                int pos = i % 8;
                berthType = switch (pos) {
                    case 1, 4 -> "LOWER";
                    case 2, 5 -> "MIDDLE";
                    case 3, 6 -> "UPPER";
                    case 7 -> "SIDE_LOWER";
                    default -> "SIDE_UPPER";
                };
            }
            seatRepository.save(Seat.builder().coach(coach).seatNumber(i).berthType(berthType).active(true).build());
        }
    }
}

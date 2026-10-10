package com.yatrarail.service;

import com.yatrarail.dto.staff.StaffPassengerDto;
import com.yatrarail.dto.staff.StaffTrainDto;
import com.yatrarail.entity.BookingStatus;
import com.yatrarail.repository.BookingRepository;
import com.yatrarail.repository.TrainRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
public class StaffService {

    private final TrainRepository trainRepository;
    private final BookingRepository bookingRepository;

    @Transactional(readOnly = true)
    public List<StaffTrainDto> getActiveTrains() {
        return trainRepository.findByActiveTrue().stream().map(StaffTrainDto::from).toList();
    }

    @Transactional(readOnly = true)
    public List<StaffPassengerDto> getPassengerManifest(Long trainId, LocalDate journeyDate) {
        // RAC passengers travel on the train, so they belong on the manifest alongside confirmed
        // and waitlisted passengers. Unpaid and expired bookings are not travelling.
        return bookingRepository
                .findByTrainIdAndJourneyDateAndStatusInOrderByCreatedAtDesc(
                        trainId,
                        journeyDate,
                        List.of(BookingStatus.CONFIRMED, BookingStatus.RAC, BookingStatus.WAITLISTED))
                .stream()
                .map(StaffPassengerDto::from)
                .toList();
    }
}
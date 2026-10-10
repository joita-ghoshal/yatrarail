package com.yatrarail.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "seats", uniqueConstraints = @UniqueConstraint(columnNames = {"coach_id", "seat_number"}),
        indexes = {
                @Index(name = "idx_seat_coach", columnList = "coach_id"),
                @Index(name = "idx_seat_active", columnList = "coach_id, active")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Seat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "coach_id", nullable = false)
    private Coach coach;

    @Column(nullable = false)
    private Integer seatNumber;

    @Column(nullable = false, length = 20)
    private String berthType; // LOWER, MIDDLE, UPPER, SIDE_LOWER, SIDE_UPPER, WINDOW, AISLE

    @Builder.Default
    @Column(nullable = false)
    private Boolean active = true;
}

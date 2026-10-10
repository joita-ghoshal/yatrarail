package com.yatrarail.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "coaches", indexes = @Index(name = "idx_coach_train", columnList = "train_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Coach {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "train_id", nullable = false)
    private Train train;

    /** e.g. B1, A1, S1, C1 */
    @Column(nullable = false, length = 10)
    private String coachCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SeatClass seatClass;

    @Column(nullable = false)
    private Integer totalSeats;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal baseFare;

    /**
     * How many RAC (Reservation Against Cancellation) entries this coach can carry on one journey
     * date. RAC passengers hold a confirmed berth but may share a seat, and are upgraded to their own
     * seat whenever a confirmed seat is cancelled.
     */
    @Builder.Default
    @Column(name = "rac_capacity", nullable = false)
    private Integer racCapacity = 2;

    @Builder.Default
    @Column(nullable = false)
    private Boolean active = true;
}

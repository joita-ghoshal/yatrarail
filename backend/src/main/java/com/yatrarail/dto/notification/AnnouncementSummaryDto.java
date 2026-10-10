package com.yatrarail.dto.notification;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * One announcement with the numbers an administrator actually publishes a notice for: how many
 * accounts it reached and how many have actually read it.
 * <p>
 * "Read" here is a receipt, per reader — the same fact the passenger's own feed shows them. A notice
 * that was read by the first person who opened it and by nobody else reports 1 of 40, which is the
 * honest answer and was previously impossible to give.
 */
@Data
@Builder
public class AnnouncementSummaryDto {
    private Long id;
    private String title;
    private String message;
    private LocalDateTime createdAt;

    /** Accounts that were live when the notice was published and can therefore be counted. */
    private long audience;

    /** How many of them have acknowledged it. Never greater than {@link #audience}. */
    private long readCount;
}
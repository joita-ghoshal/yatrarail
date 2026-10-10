package com.yatrarail.dto.quota;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Admin payload that upserts the fare concession for one (class, quota) pair. */
@Data
public class QuotaFareRequest {

    @NotBlank(message = "Seat class is required")
    @Size(max = 20, message = "Seat class must be at most 20 characters")
    private String seatClass;

    @NotBlank(message = "Quota is required")
    @Size(max = 20, message = "Quota must be at most 20 characters")
    private String quota;

    @NotNull(message = "Discount percentage is required")
    @Min(value = 0, message = "Discount cannot be negative")
    @Max(value = 90, message = "Discount must be at most 90%")
    private Integer discountPercent = 0;

    @Size(max = 200, message = "Note must be at most 200 characters")
    private String note;
}

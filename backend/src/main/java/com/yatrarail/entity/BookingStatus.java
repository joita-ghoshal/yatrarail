package com.yatrarail.entity;

public enum BookingStatus {
    /** Booking created; seats are held for a short window while the passenger pays. */
    PAYMENT_PENDING,
    /** Every passenger has their own confirmed seat/berth. */
    CONFIRMED,
    /** Every passenger is either CONFIRMED or RAC (RAC may still be upgraded to a seat). */
    RAC,
    /** At least one passenger is on the waitlist. */
    WAITLISTED,
    CANCELLED,
    PAYMENT_FAILED,
    /** The payment window elapsed without a successful payment; held seats were released. */
    EXPIRED
}

package com.yatrarail.entity;

/** What kind of disruption staff are reporting against a train on a given day. */
public enum IncidentType {
    DELAY,
    CANCELLATION,
    DIVERSION,
    BREAKDOWN,
    RESCHEDULE,
    OTHER
}
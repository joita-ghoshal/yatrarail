package com.yatrarail.entity;

/**
 * How much a reported incident matters. Kept separate from {@link IncidentType} so "a 10 minute
 * delay" and "a 10 minute delay on the last train of the day" can be triaged differently without
 * inventing a second incident scale.
 */
public enum IncidentSeverity {
    INFO,
    MINOR,
    MAJOR,
    CRITICAL
}
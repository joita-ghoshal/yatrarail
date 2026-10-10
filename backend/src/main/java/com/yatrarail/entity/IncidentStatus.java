package com.yatrarail.entity;

/** Lifecycle of a reported incident. Open means somebody still owns it. */
public enum IncidentStatus {
    OPEN,
    ACKNOWLEDGED,
    RESOLVED
}
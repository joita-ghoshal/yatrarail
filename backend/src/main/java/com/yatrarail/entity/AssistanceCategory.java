package com.yatrarail.entity;

/**
 * What a passenger is asking staff for. These are the requests a conductor can actually act on
 * at the platform rather than routing through a call centre.
 */
public enum AssistanceCategory {
    WHEELCHAIR,
    LUGGAGE,
    MEDICAL,
    UNACCOMPANIED_MINOR,
    LANGUAGE,
    OTHER
}
package com.yatrarail.entity;

public enum SeatClass {
    FIRST_AC("1A"),
    SECOND_AC("2A"),
    THIRD_AC("3A"),
    SLEEPER("SL"),
    CHAIR_CAR("CC"),
    SECOND_SEATING("2S");

    private final String label;

    SeatClass(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}

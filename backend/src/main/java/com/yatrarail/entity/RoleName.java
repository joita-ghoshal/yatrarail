package com.yatrarail.entity;

public enum RoleName {
    PASSENGER,
    ADMIN,
    STAFF,
    /**
     * Short-lived, single-purpose account created for guests who want to book without registering.
     * It can never be promoted to STAFF/ADMIN, holds no usable password, and its session lifetime is
     * enforced on every request (see TemporaryAccountService).
     */
    TEMP_PASSENGER
}

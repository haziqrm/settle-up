package com.settleup.backend.settlement;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

/** Names match the CHECK constraint on payments.status in V1__init.sql. */
public enum PaymentStatus {
    PLANNED,
    AWAITING_AUTHORISATION,
    PENDING,
    CONFIRMED,
    FAILED,
    EXPIRED,
    CANCELLED;

    /**
     * Allowed moves. Once the payer has approved (PENDING), only the bank decides the outcome:
     * money may already be moving, so it can no longer be cancelled or expired by us.
     * AWAITING_AUTHORISATION -> CONFIRMED is allowed because providers can skip PENDING.
     */
    public Set<PaymentStatus> allowedNext() {
        return switch (this) {
            case PLANNED -> EnumSet.of(AWAITING_AUTHORISATION, EXPIRED, CANCELLED);
            case AWAITING_AUTHORISATION -> EnumSet.of(PENDING, CONFIRMED, FAILED, EXPIRED, CANCELLED);
            case PENDING -> EnumSet.of(CONFIRMED, FAILED);
            case CONFIRMED, FAILED, EXPIRED, CANCELLED -> EnumSet.noneOf(PaymentStatus.class);
        };
    }

    public boolean canMoveTo(PaymentStatus next) {
        return allowedNext().contains(next);
    }

    public boolean isTerminal() {
        return allowedNext().isEmpty();
    }

    /** States a payment may be in to move to {@code target}; used for compare-and-set updates. */
    public static Set<PaymentStatus> sourcesFor(PaymentStatus target) {
        EnumSet<PaymentStatus> sources = EnumSet.noneOf(PaymentStatus.class);
        Arrays.stream(values()).filter(s -> s.canMoveTo(target)).forEach(sources::add);
        return sources;
    }
}

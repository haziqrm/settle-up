package com.settleup.backend.settlement;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.settleup.backend.settlement.PaymentStatus.*;
import static org.junit.jupiter.api.Assertions.*;

class PaymentStatusTest {

    /** The full spec: every allowed move. Any pair not listed must be rejected. */
    private static final Set<String> ALLOWED = Set.of(
            "PLANNED -> AWAITING_AUTHORISATION",
            "PLANNED -> EXPIRED",
            "PLANNED -> CANCELLED",
            "AWAITING_AUTHORISATION -> PENDING",
            "AWAITING_AUTHORISATION -> CONFIRMED",
            "AWAITING_AUTHORISATION -> FAILED",
            "AWAITING_AUTHORISATION -> EXPIRED",
            "AWAITING_AUTHORISATION -> CANCELLED",
            "PENDING -> CONFIRMED",
            "PENDING -> FAILED");

    @Test
    void everyPairMatchesTheSpec() {
        for (PaymentStatus from : values()) {
            for (PaymentStatus to : values()) {
                String move = from + " -> " + to;
                assertEquals(ALLOWED.contains(move), from.canMoveTo(to), move);
            }
        }
    }

    @Test
    void finalStatesAreExactlyTheFourOutcomes() {
        Set<PaymentStatus> terminal = Arrays.stream(values()).filter(PaymentStatus::isTerminal)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(PaymentStatus.class)));
        assertEquals(EnumSet.of(CONFIRMED, FAILED, EXPIRED, CANCELLED), terminal);
    }

    @Test
    void noStateMovesToItself() {
        for (PaymentStatus s : values()) assertFalse(s.canMoveTo(s), s.name());
    }

    @Test
    void nothingMovesBackToPlanned() {
        assertTrue(sourcesFor(PLANNED).isEmpty());
    }

    @Test
    void onceApprovedOnlyTheBankDecides() {
        assertEquals(EnumSet.of(CONFIRMED, FAILED), PENDING.allowedNext());
    }

    @Test
    void everyNonFinalStateCanStillBeConfirmed() {
        for (PaymentStatus s : values()) {
            if (!s.isTerminal()) assertTrue(reachable(s).contains(CONFIRMED), s.name());
        }
    }

    @Test
    void sourcesForIsTheInverseOfAllowedNext() {
        assertEquals(EnumSet.of(AWAITING_AUTHORISATION, PENDING), sourcesFor(CONFIRMED));
        for (PaymentStatus target : values()) {
            for (PaymentStatus from : values()) {
                assertEquals(from.canMoveTo(target), sourcesFor(target).contains(from), from + " -> " + target);
            }
        }
    }

    @Test
    void namesMatchTheDatabaseCheckConstraint() throws IOException {
        String sql;
        try (InputStream in = getClass().getResourceAsStream("/db/migration/V1__init.sql")) {
            assertNotNull(in, "V1__init.sql not on classpath");
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        String payments = sql.substring(sql.indexOf("CREATE TABLE payments"), sql.indexOf("idempotency_key"));
        Matcher check = Pattern.compile("CHECK \\(status IN \\(([^)]*)\\)\\)").matcher(payments);
        assertTrue(check.find(), "payments.status CHECK constraint not found");

        Set<String> dbValues = new HashSet<>();
        Matcher quoted = Pattern.compile("'([A-Z_]+)'").matcher(check.group(1));
        while (quoted.find()) dbValues.add(quoted.group(1));

        Set<String> enumValues = Arrays.stream(values()).map(Enum::name).collect(Collectors.toSet());
        assertEquals(dbValues, enumValues);
    }

    private static Set<PaymentStatus> reachable(PaymentStatus start) {
        Set<PaymentStatus> seen = EnumSet.noneOf(PaymentStatus.class);
        Deque<PaymentStatus> queue = new ArrayDeque<>(start.allowedNext());
        while (!queue.isEmpty()) {
            PaymentStatus s = queue.poll();
            if (seen.add(s)) queue.addAll(s.allowedNext());
        }
        return seen;
    }
}

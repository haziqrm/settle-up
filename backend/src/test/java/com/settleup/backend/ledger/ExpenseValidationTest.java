package com.settleup.backend.ledger;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ExpenseValidationTest {

    private static final Set<Long> MEMBERS = Set.of(1L, 2L, 3L);

    private static String rejection(long paidBy, List<Long> participants, long total, String description) {
        return assertThrows(InvalidExpenseException.class,
                () -> ExpenseValidation.check(MEMBERS, paidBy, participants, total, description)).getMessage();
    }

    @Test
    void acceptsValidExpense() {
        assertDoesNotThrow(() -> ExpenseValidation.check(MEMBERS, 1, List.of(1L, 2L, 3L), 3000, "Dinner"));
    }

    @Test
    void acceptsPayerWhoIsNotAParticipant() {
        assertDoesNotThrow(() -> ExpenseValidation.check(MEMBERS, 3, List.of(1L, 2L), 1000, "Taxi"));
    }

    @Test
    void rejectsZeroAndNegativeAmounts() {
        assertTrue(rejection(1, List.of(1L), 0, "x").contains("totalPence"));
        assertTrue(rejection(1, List.of(1L), -500, "x").contains("totalPence"));
    }

    @Test
    void rejectsMissingDescription() {
        assertTrue(rejection(1, List.of(1L), 100, null).contains("description"));
        assertTrue(rejection(1, List.of(1L), 100, "   ").contains("description"));
    }

    @Test
    void rejectsEmptyNullOrDuplicateParticipants() {
        assertTrue(rejection(1, List.of(), 100, "x").contains("participant"));
        assertTrue(rejection(1, null, 100, "x").contains("participant"));
        assertTrue(rejection(1, Arrays.asList(1L, null), 100, "x").contains("null"));
        assertTrue(rejection(1, List.of(1L, 2L, 1L), 100, "x").contains("repeat"));
    }

    @Test
    void rejectsNonMemberPayer() {
        assertEquals("payer 9 is not a member of this group", rejection(9, List.of(1L, 2L), 100, "x"));
    }

    @Test
    void rejectsNonMemberParticipantsListingThemInOrder() {
        assertEquals("participants [7, 8] are not members of this group",
                rejection(1, List.of(8L, 1L, 7L), 100, "x"));
    }
}

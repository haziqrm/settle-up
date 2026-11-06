package com.settleup.backend.ledger;

import com.settleup.backend.TestcontainersConfiguration;
import com.settleup.backend.group.GroupNotFoundException;
import com.settleup.backend.ledger.LedgerFixtures.Group;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ExpenseServiceIntegrationTest {

    @Autowired ExpenseService service;
    @Autowired JdbcTemplate jdbc;

    LedgerFixtures fx;

    @BeforeEach
    void setUp() {
        fx = new LedgerFixtures(jdbc);
    }

    // --- ledger always balances -------------------------------------------------------

    @Test
    void groupLedgerSumsToZeroAfterSeveralAwkwardExpenses() {
        Group g = fx.newGroupWithMembers(4);
        long a = g.memberIds().get(0), b = g.memberIds().get(1), c = g.memberIds().get(2), d = g.memberIds().get(3);

        service.addExpense(g.id(), a, List.of(a, b, c), 1000, "£10 three ways");
        service.addExpense(g.id(), b, List.of(a, c, d), 1, "1p three ways, payer not splitting");
        service.addExpense(g.id(), c, List.of(d, c, b, a), 9_999, "odd total, unsorted participants");
        service.addExpense(g.id(), d, List.of(d), 500, "payer alone: no ledger lines");
        service.addExpense(g.id(), a, List.of(b), 250, "one-to-one");

        assertEquals(5, fx.expenseCount(g.id()));
        assertEquals(0, fx.ledgerSum(g.id()));
    }

    @Test
    void writesTheExpectedBalances() {
        Group g = fx.newGroupWithMembers(3);
        long a = g.memberIds().get(0), b = g.memberIds().get(1), c = g.memberIds().get(2);

        service.addExpense(g.id(), a, List.of(a, b, c), 1000, "Dinner");

        // Shares are 334/333/333 by ascending user id, so `a` (lowest id) takes the extra penny.
        assertEquals(1000 - 334, fx.balanceOf(g.id(), a));
        assertEquals(-333, fx.balanceOf(g.id(), b));
        assertEquals(-333, fx.balanceOf(g.id(), c));
    }

    // --- append-only trigger ----------------------------------------------------------

    @Test
    void updateDeleteAndTruncateOnLedgerAreRejected() {
        Group g = fx.newGroupWithMembers(2);
        service.addExpense(g.id(), g.memberIds().get(0), g.memberIds(), 200, "Coffee");
        long entryId = jdbc.queryForObject(
                "SELECT id FROM ledger_entries WHERE group_id = ? LIMIT 1", Long.class, g.id());

        assertAppendOnlyRejection(() -> jdbc.update("UPDATE ledger_entries SET amount_pence = 1 WHERE id = ?", entryId));
        assertAppendOnlyRejection(() -> jdbc.update("DELETE FROM ledger_entries WHERE id = ?", entryId));
        assertAppendOnlyRejection(() -> jdbc.execute("TRUNCATE ledger_entries"));
        // CASCADE from a parent table would also empty the ledger; the trigger still fires.
        assertAppendOnlyRejection(() -> jdbc.execute("TRUNCATE expenses CASCADE"));

        assertEquals(2, fx.ledgerRowCount(g.id()));
        assertEquals(0, fx.ledgerSum(g.id()));
    }

    private static void assertAppendOnlyRejection(Runnable statement) {
        DataAccessException e = assertThrows(DataAccessException.class, statement::run);
        assertTrue(e.getMostSpecificCause().getMessage().contains("append-only"), e.getMessage());
    }

    // --- validation -------------------------------------------------------------------

    @Test
    void zeroOrNegativeAmountIsRejectedAndNothingIsWritten() {
        Group g = fx.newGroupWithMembers(2);
        long payer = g.memberIds().get(0);

        assertThrows(InvalidExpenseException.class, () -> service.addExpense(g.id(), payer, g.memberIds(), 0, "Zero"));
        assertThrows(InvalidExpenseException.class, () -> service.addExpense(g.id(), payer, g.memberIds(), -100, "Negative"));

        assertEquals(0, fx.expenseCount(g.id()));
        assertEquals(0, fx.ledgerRowCount(g.id()));
    }

    @Test
    void nonMemberPayerIsRejected() {
        Group g = fx.newGroupWithMembers(2);
        long outsider = fx.newUser("Outsider");

        InvalidExpenseException e = assertThrows(InvalidExpenseException.class,
                () -> service.addExpense(g.id(), outsider, g.memberIds(), 1000, "Not my group"));
        assertTrue(e.getMessage().contains("payer"));
        assertEquals(0, fx.expenseCount(g.id()));
    }

    @Test
    void nonMemberParticipantIsRejected() {
        Group g = fx.newGroupWithMembers(2);
        long outsider = fx.newUser("Outsider");

        InvalidExpenseException e = assertThrows(InvalidExpenseException.class,
                () -> service.addExpense(g.id(), g.memberIds().get(0), List.of(g.memberIds().get(1), outsider), 1000, "Dinner"));
        assertTrue(e.getMessage().contains(String.valueOf(outsider)));
        assertEquals(0, fx.expenseCount(g.id()));
    }

    @Test
    void memberOfAnotherGroupIsRejected() {
        Group mine = fx.newGroupWithMembers(2);
        Group other = fx.newGroupWithMembers(1);

        assertThrows(InvalidExpenseException.class,
                () -> service.addExpense(mine.id(), mine.memberIds().get(0),
                        List.of(mine.memberIds().get(1), other.memberIds().get(0)), 1000, "Wrong group"));
        assertEquals(0, fx.expenseCount(mine.id()));
    }

    @Test
    void unknownGroupIsRejected() {
        long user = fx.newUser("Lonely");
        assertThrows(GroupNotFoundException.class,
                () -> service.addExpense(Long.MAX_VALUE, user, List.of(user), 100, "Nowhere"));
    }
}

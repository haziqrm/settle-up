package com.settleup.backend.settlement;

import com.settleup.backend.TestcontainersConfiguration;
import com.settleup.backend.ledger.ExpenseService;
import com.settleup.backend.ledger.LedgerFixtures;
import com.settleup.backend.ledger.LedgerFixtures.Group;
import com.settleup.backend.settlement.PaymentTransitionService.Outcome;
import com.settleup.backend.settlement.SettlementExceptions.IllegalPaymentTransitionException;
import com.settleup.backend.settlement.SettlementExceptions.PaymentNotFoundException;
import com.settleup.backend.settlement.SettlementRunRepository.PaymentRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.settleup.backend.settlement.PaymentStatus.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PaymentTransitionIntegrationTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired ExpenseService expenses;
    @Autowired SettlementRunService runs;
    @Autowired PaymentTransitionService transitions;

    LedgerFixtures fx;
    Group g;
    long a, b, c;
    PaymentRow bPaysA, cPaysA;

    /** Group of three, a paid £30 for everyone: the run has b -> a 1000 and c -> a 1000. */
    @BeforeEach
    void setUp() {
        fx = new LedgerFixtures(jdbc);
        g = fx.newGroupWithMembers(3);
        a = g.memberIds().get(0);
        b = g.memberIds().get(1);
        c = g.memberIds().get(2);
        expenses.addExpense(g.id(), a, List.of(a, b, c), 3000, "Groceries");
        List<PaymentRow> payments = runs.getRun(runs.createRun(g.id(), a)).payments();
        bPaysA = payments.get(0);
        cPaysA = payments.get(1);
    }

    private void moveToPending(long paymentId) {
        assertEquals(Outcome.APPLIED, transitions.moveTo(paymentId, AWAITING_AUTHORISATION));
        assertEquals(Outcome.APPLIED, transitions.moveTo(paymentId, PENDING));
    }

    private int paymentLedgerRows(long paymentId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM ledger_entries WHERE payment_id = ?", Integer.class, paymentId);
    }

    private PaymentStatus statusOf(long paymentId) {
        return PaymentStatus.valueOf(jdbc.queryForObject("SELECT status FROM payments WHERE id = ?", String.class, paymentId));
    }

    // --- confirming writes the ledger -------------------------------------------------

    @Test
    void confirmingEveryPaymentSettlesTheGroupToZero() {
        assertEquals(2000, fx.balanceOf(g.id(), a));

        for (PaymentRow p : List.of(bPaysA, cPaysA)) {
            moveToPending(p.id());
            assertEquals(Outcome.APPLIED, transitions.moveTo(p.id(), CONFIRMED));
            assertEquals(2, paymentLedgerRows(p.id()));
        }

        assertEquals(0, fx.balanceOf(g.id(), a));
        assertEquals(0, fx.balanceOf(g.id(), b));
        assertEquals(0, fx.balanceOf(g.id(), c));
        assertEquals(0, fx.ledgerSum(g.id()));
    }

    @Test
    void confirmedPaymentWritesPayerPlusAndPayeeMinus() {
        moveToPending(bPaysA.id());
        transitions.moveTo(bPaysA.id(), CONFIRMED);

        assertEquals(1000L, jdbc.queryForObject(
                "SELECT amount_pence FROM ledger_entries WHERE payment_id = ? AND user_id = ?", Long.class, bPaysA.id(), b));
        assertEquals(-1000L, jdbc.queryForObject(
                "SELECT amount_pence FROM ledger_entries WHERE payment_id = ? AND user_id = ?", Long.class, bPaysA.id(), a));
        // Only b has paid so far: a is still owed c's share.
        assertEquals(1000, fx.balanceOf(g.id(), a));
        assertEquals(0, fx.balanceOf(g.id(), b));
        assertEquals(-1000, fx.balanceOf(g.id(), c));
    }

    @Test
    void providerMaySkipPending() {
        transitions.moveTo(bPaysA.id(), AWAITING_AUTHORISATION);
        assertEquals(Outcome.APPLIED, transitions.moveTo(bPaysA.id(), CONFIRMED));
        assertEquals(2, paymentLedgerRows(bPaysA.id()));
    }

    @Test
    void transitionsOtherThanConfirmWriteNoLedgerRows() {
        transitions.moveTo(bPaysA.id(), AWAITING_AUTHORISATION);
        transitions.moveTo(bPaysA.id(), PENDING);
        transitions.moveTo(bPaysA.id(), FAILED);
        transitions.moveTo(cPaysA.id(), CANCELLED);

        assertEquals(0, paymentLedgerRows(bPaysA.id()));
        assertEquals(0, paymentLedgerRows(cPaysA.id()));
        assertEquals(2000, fx.balanceOf(g.id(), a));
    }

    // --- duplicates and races ---------------------------------------------------------

    @Test
    void duplicateConfirmIsANoOp() {
        moveToPending(bPaysA.id());
        assertEquals(Outcome.APPLIED, transitions.moveTo(bPaysA.id(), CONFIRMED));
        assertEquals(Outcome.ALREADY_IN_STATE, transitions.moveTo(bPaysA.id(), CONFIRMED));
        assertEquals(Outcome.ALREADY_IN_STATE, transitions.moveTo(bPaysA.id(), CONFIRMED));

        assertEquals(2, paymentLedgerRows(bPaysA.id()));
        assertEquals(1000, fx.balanceOf(g.id(), a));
    }

    @Test
    void concurrentConfirmsWriteTheLedgerExactlyOnce() throws Exception {
        moveToPending(bPaysA.id());
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Outcome>> results = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return transitions.moveTo(bPaysA.id(), CONFIRMED);
                }));
            }
            start.countDown();

            int applied = 0, duplicates = 0;
            for (Future<Outcome> f : results) {
                Outcome o = f.get(30, TimeUnit.SECONDS);
                if (o == Outcome.APPLIED) applied++;
                else duplicates++;
            }
            assertEquals(1, applied);
            assertEquals(threads - 1, duplicates);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(2, paymentLedgerRows(bPaysA.id()));
        assertEquals(0, fx.ledgerSum(g.id()));
    }

    @Test
    void uniqueIndexIsABackstopAgainstDoubleLedgerRows() {
        moveToPending(bPaysA.id());
        transitions.moveTo(bPaysA.id(), CONFIRMED);

        assertThrows(DuplicateKeyException.class, () -> jdbc.update("""
                INSERT INTO ledger_entries (group_id, user_id, amount_pence, payment_id) VALUES (?, ?, ?, ?)
                """, g.id(), b, 1000, bPaysA.id()));
    }

    // --- illegal moves ----------------------------------------------------------------

    @Test
    void cannotConfirmAPlannedPayment() {
        IllegalPaymentTransitionException e = assertThrows(IllegalPaymentTransitionException.class,
                () -> transitions.moveTo(bPaysA.id(), CONFIRMED));
        assertTrue(e.getMessage().contains("PLANNED to CONFIRMED"), e.getMessage());
        assertEquals(PLANNED, statusOf(bPaysA.id()));
        assertEquals(0, paymentLedgerRows(bPaysA.id()));
    }

    @Test
    void cannotConfirmAFailedPayment() {
        moveToPending(bPaysA.id());
        transitions.moveTo(bPaysA.id(), FAILED);

        assertThrows(IllegalPaymentTransitionException.class, () -> transitions.moveTo(bPaysA.id(), CONFIRMED));
        assertEquals(FAILED, statusOf(bPaysA.id()));
        assertEquals(0, paymentLedgerRows(bPaysA.id()));
    }

    @Test
    void cannotCancelOnceApproved() {
        moveToPending(bPaysA.id());
        assertThrows(IllegalPaymentTransitionException.class, () -> transitions.moveTo(bPaysA.id(), CANCELLED));
        assertEquals(PENDING, statusOf(bPaysA.id()));
    }

    @Test
    void unknownPaymentIsNotFound() {
        assertThrows(PaymentNotFoundException.class, () -> transitions.moveTo(Long.MAX_VALUE, CONFIRMED));
    }
}

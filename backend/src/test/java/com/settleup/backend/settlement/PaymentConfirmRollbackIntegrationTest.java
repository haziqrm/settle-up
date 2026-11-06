package com.settleup.backend.settlement;

import com.settleup.backend.TestcontainersConfiguration;
import com.settleup.backend.ledger.ExpenseService;
import com.settleup.backend.ledger.LedgerFixtures;
import com.settleup.backend.ledger.LedgerFixtures.Group;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.List;

import static com.settleup.backend.settlement.PaymentStatus.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;

/** CONFIRMED status and its ledger rows commit together, or not at all. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PaymentConfirmRollbackIntegrationTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired ExpenseService expenses;
    @Autowired SettlementRunService runs;
    @Autowired PaymentTransitionService transitions;
    @MockitoSpyBean PaymentRepository payments;

    @Test
    void failedLedgerWriteLeavesThePaymentPending() {
        LedgerFixtures fx = new LedgerFixtures(jdbc);
        Group g = fx.newGroupWithMembers(2);
        long a = g.memberIds().get(0), b = g.memberIds().get(1);
        expenses.addExpense(g.id(), a, List.of(b), 1000, "Dinner");
        long paymentId = runs.getRun(runs.createRun(g.id(), a)).payments().getFirst().id();
        transitions.moveTo(paymentId, AWAITING_AUTHORISATION);
        transitions.moveTo(paymentId, PENDING);

        doThrow(new DataIntegrityViolationException("simulated ledger failure"))
                .when(payments).insertLedgerRows(anyLong(), any());

        assertThrows(DataIntegrityViolationException.class, () -> transitions.moveTo(paymentId, CONFIRMED));

        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM payments WHERE id = ?", String.class, paymentId));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ledger_entries WHERE payment_id = ?", Integer.class, paymentId));
    }
}

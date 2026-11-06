package com.settleup.backend.ledger;

import com.settleup.backend.TestcontainersConfiguration;
import com.settleup.backend.ledger.LedgerFixtures.Group;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * Proves the @Transactional boundary: the expense row is really inserted, then the ledger
 * insert fails, and the expense must be gone too. Kept in its own class because the spy
 * changes the Spring context, and other tests should keep using the real repository.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ExpenseRollbackIntegrationTest {

    @Autowired ExpenseService service;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean ExpenseRepository repository;

    @Test
    void failedLedgerInsertRollsBackTheExpense() {
        LedgerFixtures fx = new LedgerFixtures(jdbc);
        Group g = fx.newGroupWithMembers(2);
        doThrow(new DataIntegrityViolationException("simulated ledger failure"))
                .when(repository).insertLedgerLines(anyLong(), anyLong(), anyList());

        assertThrows(DataIntegrityViolationException.class,
                () -> service.addExpense(g.id(), g.memberIds().get(0), g.memberIds(), 1000, "Doomed"));

        // The expense insert did run (real method on the spy) before the failure...
        verify(repository).insertExpense(g.id(), g.memberIds().get(0), "Doomed", 1000);
        // ...but the transaction rolled it back.
        assertEquals(0, fx.expenseCount(g.id()));
        assertEquals(0, fx.ledgerRowCount(g.id()));
    }
}

package com.settleup.backend.ledger;

import com.settleup.backend.ledger.ExpenseLedger.LedgerLine;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Plain SQL for expenses and their ledger rows. Kept separate from ExpenseService so the
 * transaction boundary lives in the service, and so tests can make one insert fail and
 * check that the other is rolled back.
 */
@Repository
public class ExpenseRepository {

    private final JdbcTemplate jdbc;

    public ExpenseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts the expense and returns its generated id. */
    public long insertExpense(long groupId, long paidBy, String description, long amountPence) {
        Long id = jdbc.queryForObject("""
                INSERT INTO expenses (group_id, paid_by, description, amount_pence)
                VALUES (?, ?, ?, ?)
                RETURNING id
                """, Long.class, groupId, paidBy, description, amountPence);
        return id;
    }

    public void insertLedgerLines(long groupId, long expenseId, List<LedgerLine> lines) {
        // One JDBC batch rather than a round trip per line.
        jdbc.batchUpdate("""
                INSERT INTO ledger_entries (group_id, user_id, amount_pence, expense_id)
                VALUES (?, ?, ?, ?)
                """, lines, lines.size(), (ps, line) -> {
            ps.setLong(1, groupId);
            ps.setLong(2, line.userId());
            ps.setLong(3, line.amountPence());
            ps.setLong(4, expenseId);
        });
    }
}

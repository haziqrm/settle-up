package com.settleup.backend.settlement;

import com.settleup.backend.settlement.Netting.Transfer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

@Repository
public class SettlementRunRepository {

    public record RunRow(long id, long groupId, long createdBy, String status, Instant deadlineAt, Instant createdAt) {}

    public record PaymentRow(long id, long payerId, String payerName, long payeeId, String payeeName,
                             long amountPence, PaymentStatus status, String idempotencyKey) {}

    private final JdbcTemplate jdbc;

    public SettlementRunRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Long> findOpenRunId(long groupId) {
        return jdbc.queryForList("SELECT id FROM settlement_runs WHERE group_id = ? AND status = 'OPEN'",
                Long.class, groupId).stream().findFirst();
    }

    public long insertRun(long groupId, long createdBy, Instant deadlineAt) {
        return jdbc.queryForObject("""
                INSERT INTO settlement_runs (group_id, created_by, deadline_at)
                VALUES (?, ?, ?)
                RETURNING id
                """, Long.class, groupId, createdBy, OffsetDateTime.ofInstant(deadlineAt, ZoneOffset.UTC));
    }

    public void insertPlannedPayments(long runId, List<Transfer> transfers) {
        jdbc.batchUpdate("""
                INSERT INTO payments (run_id, payer_id, payee_id, amount_pence, idempotency_key)
                VALUES (?, ?, ?, ?, ?)
                """, transfers, transfers.size(), (ps, t) -> {
            ps.setLong(1, runId);
            ps.setLong(2, t.payerId());
            ps.setLong(3, t.payeeId());
            ps.setLong(4, t.amountPence());
            ps.setString(5, idempotencyKey(runId, t));
        });
    }

    static String idempotencyKey(long runId, Transfer t) {
        return "run-" + runId + "-" + t.payerId() + "-" + t.payeeId();
    }

    public Optional<RunRow> findRun(long runId) {
        return jdbc.query("""
                SELECT id, group_id, created_by, status, deadline_at, created_at
                FROM settlement_runs WHERE id = ?
                """, (rs, i) -> new RunRow(rs.getLong("id"), rs.getLong("group_id"), rs.getLong("created_by"),
                rs.getString("status"), instant(rs, "deadline_at"), instant(rs, "created_at")), runId)
                .stream().findFirst();
    }

    public List<PaymentRow> paymentsOf(long runId) {
        return jdbc.query("""
                SELECT p.id, p.payer_id, payer.display_name AS payer_name, p.payee_id,
                       payee.display_name AS payee_name, p.amount_pence, p.status, p.idempotency_key
                FROM payments p
                JOIN users payer ON payer.id = p.payer_id
                JOIN users payee ON payee.id = p.payee_id
                WHERE p.run_id = ?
                ORDER BY p.id
                """, (rs, i) -> new PaymentRow(rs.getLong("id"), rs.getLong("payer_id"), rs.getString("payer_name"),
                rs.getLong("payee_id"), rs.getString("payee_name"), rs.getLong("amount_pence"),
                PaymentStatus.valueOf(rs.getString("status")), rs.getString("idempotency_key")), runId);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }
}

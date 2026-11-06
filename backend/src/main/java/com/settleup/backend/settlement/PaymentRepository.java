package com.settleup.backend.settlement;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public class PaymentRepository {

    public record MovedPayment(long groupId, long payerId, long payeeId, long amountPence) {}

    private final JdbcTemplate jdbc;

    public PaymentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<PaymentStatus> statusOf(long paymentId) {
        return jdbc.queryForList("SELECT status FROM payments WHERE id = ?", String.class, paymentId)
                .stream().findFirst().map(PaymentStatus::valueOf);
    }

    /**
     * Compare-and-set: moves the payment to {@code target} only if it is currently in one of
     * {@code from}. Postgres locks the row, so of two concurrent callers exactly one gets the
     * row back; the other re-reads the new status, no longer matches, and gets empty.
     */
    public Optional<MovedPayment> compareAndSetStatus(long paymentId, Set<PaymentStatus> from, PaymentStatus target) {
        if (from.isEmpty()) return Optional.empty();
        String placeholders = String.join(", ", Collections.nCopies(from.size(), "?"));
        Object[] args = new Object[from.size() + 2];
        args[0] = target.name();
        args[1] = paymentId;
        int i = 2;
        for (PaymentStatus s : from) args[i++] = s.name();

        List<MovedPayment> rows = jdbc.query("""
                UPDATE payments p
                SET status = ?, updated_at = now()
                FROM settlement_runs r
                WHERE p.id = ? AND r.id = p.run_id AND p.status IN (%s)
                RETURNING r.group_id, p.payer_id, p.payee_id, p.amount_pence
                """.formatted(placeholders),
                (rs, n) -> new MovedPayment(rs.getLong("group_id"), rs.getLong("payer_id"),
                        rs.getLong("payee_id"), rs.getLong("amount_pence")),
                args);
        return rows.stream().findFirst();
    }

    /** Everything Pay needs to decide whether this payment can be started. */
    public record PayablePayment(long id, String runStatus, Instant runDeadline, long payerId, long payeeId,
                                 long amountPence, PaymentStatus status, String idempotencyKey,
                                 String provider, String providerPaymentId) {}

    public Optional<PayablePayment> findPayable(long paymentId) {
        return jdbc.query("""
                SELECT p.id, r.status AS run_status, r.deadline_at, p.payer_id, p.payee_id, p.amount_pence,
                       p.status, p.idempotency_key, p.provider, p.provider_payment_id
                FROM payments p JOIN settlement_runs r ON r.id = p.run_id
                WHERE p.id = ?
                """, (rs, n) -> new PayablePayment(rs.getLong("id"), rs.getString("run_status"),
                rs.getObject("deadline_at", OffsetDateTime.class).toInstant(), rs.getLong("payer_id"),
                rs.getLong("payee_id"), rs.getLong("amount_pence"), PaymentStatus.valueOf(rs.getString("status")),
                rs.getString("idempotency_key"), rs.getString("provider"), rs.getString("provider_payment_id")),
                paymentId).stream().findFirst();
    }

    /** PLANNED -> AWAITING_AUTHORISATION, recording the provider's reference in the same update. */
    public boolean compareAndSetAwaitingAuthorisation(long paymentId, String provider, String providerPaymentId,
                                                      Instant authExpiresAt) {
        return jdbc.update("""
                UPDATE payments
                SET status = 'AWAITING_AUTHORISATION', provider = ?, provider_payment_id = ?,
                    auth_expires_at = ?, updated_at = now()
                WHERE id = ? AND status = 'PLANNED'
                """, provider, providerPaymentId, OffsetDateTime.ofInstant(authExpiresAt, ZoneOffset.UTC),
                paymentId) == 1;
    }

    /** Payer's balance goes up (debt paid), payee's goes down (no longer owed). Sums to zero. */
    public void insertLedgerRows(long paymentId, MovedPayment p) {
        jdbc.update("""
                INSERT INTO ledger_entries (group_id, user_id, amount_pence, payment_id)
                VALUES (?, ?, ?, ?), (?, ?, ?, ?)
                """,
                p.groupId(), p.payerId(), p.amountPence(), paymentId,
                p.groupId(), p.payeeId(), -p.amountPence(), paymentId);
    }
}

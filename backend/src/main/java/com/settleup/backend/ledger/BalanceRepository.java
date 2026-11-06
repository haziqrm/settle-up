package com.settleup.backend.ledger;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class BalanceRepository {

    /** Positive: the group owes this member. Negative: this member owes the group. */
    public record MemberBalance(long userId, String displayName, long balancePence) {}

    private final JdbcTemplate jdbc;

    public BalanceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Balances are always derived from the ledger, never stored, so they cannot drift from
     * the entries. Starting from group_members with a LEFT JOIN means a member with no
     * entries still appears, with 0. One SELECT reads one consistent snapshot, so the
     * returned balances sum to zero even while expenses are being added.
     */
    public List<MemberBalance> balancesOf(long groupId) {
        return jdbc.query("""
                SELECT u.id, u.display_name, COALESCE(SUM(l.amount_pence), 0) AS balance
                FROM group_members m
                JOIN users u ON u.id = m.user_id
                LEFT JOIN ledger_entries l ON l.group_id = m.group_id AND l.user_id = m.user_id
                WHERE m.group_id = ?
                GROUP BY u.id, u.display_name
                ORDER BY u.id
                """,
                (rs, i) -> new MemberBalance(rs.getLong("id"), rs.getString("display_name"), rs.getLong("balance")),
                groupId);
    }
}

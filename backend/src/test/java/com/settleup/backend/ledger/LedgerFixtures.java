package com.settleup.backend.ledger;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds a fresh group per test instead of cleaning tables between tests. The ledger is
 * append-only (DELETE and TRUNCATE are blocked), so tests share one database and each
 * one only looks at its own group.
 */
final class LedgerFixtures {

    record Group(long id, List<Long> memberIds) {}

    private final JdbcTemplate jdbc;

    LedgerFixtures(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    long newUser(String name) {
        return jdbc.queryForObject("INSERT INTO users (display_name) VALUES (?) RETURNING id", Long.class, name);
    }

    Group newGroupWithMembers(int memberCount) {
        long groupId = jdbc.queryForObject("INSERT INTO groups (name) VALUES ('Test group') RETURNING id", Long.class);
        List<Long> members = new ArrayList<>();
        for (int i = 0; i < memberCount; i++) {
            long userId = newUser("Member " + i);
            jdbc.update("INSERT INTO group_members (group_id, user_id) VALUES (?, ?)", groupId, userId);
            members.add(userId);
        }
        return new Group(groupId, members);
    }

    long ledgerSum(long groupId) {
        return jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount_pence), 0) FROM ledger_entries WHERE group_id = ?", Long.class, groupId);
    }

    long balanceOf(long groupId, long userId) {
        return jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount_pence), 0) FROM ledger_entries WHERE group_id = ? AND user_id = ?",
                Long.class, groupId, userId);
    }

    int expenseCount(long groupId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM expenses WHERE group_id = ?", Integer.class, groupId);
    }

    int ledgerRowCount(long groupId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM ledger_entries WHERE group_id = ?", Integer.class, groupId);
    }
}

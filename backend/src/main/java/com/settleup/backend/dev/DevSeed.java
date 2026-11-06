package com.settleup.backend.dev;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * Creates a few users and a group at startup so the API can be tried without auth.
 * Only exists under the "dev" profile, so it can never run in tests or production.
 * Idempotent: if the seed group already exists it does nothing, so restarts are safe
 * against the persistent Docker Compose database.
 */
@Component
@Profile("dev")
public class DevSeed implements ApplicationRunner {

    static final String GROUP_NAME = "Flat 12 (dev seed)";
    static final List<String> USERS = List.of("Alex", "Ben", "Cara");

    private static final Logger log = LoggerFactory.getLogger(DevSeed.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public DevSeed(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    @Override
    public void run(ApplicationArguments args) {
        // One transaction, so a failure halfway never leaves a group without its members.
        // TransactionTemplate rather than @Transactional: explicit, and no proxy subtleties
        // around a lifecycle callback.
        tx.executeWithoutResult(status -> seed());
    }

    private void seed() {
        List<Long> existing = jdbc.queryForList("SELECT id FROM groups WHERE name = ?", Long.class, GROUP_NAME);
        if (!existing.isEmpty()) {
            log.info("Dev seed already present: group {}", existing.getFirst());
            return;
        }

        long groupId = jdbc.queryForObject("INSERT INTO groups (name) VALUES (?) RETURNING id", Long.class, GROUP_NAME);
        List<String> summary = new ArrayList<>();
        for (int i = 0; i < USERS.size(); i++) {
            String name = USERS.get(i);
            long userId = jdbc.queryForObject(
                    "INSERT INTO users (display_name) VALUES (?) RETURNING id", Long.class, name);
            jdbc.update("INSERT INTO group_members (group_id, user_id, role) VALUES (?, ?, ?)",
                    groupId, userId, i == 0 ? "OWNER" : "MEMBER");
            summary.add(name + "=" + userId);
        }
        log.info("Dev seed created group {} with users {}", groupId, summary);
    }
}

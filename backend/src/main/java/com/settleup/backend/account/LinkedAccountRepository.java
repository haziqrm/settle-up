package com.settleup.backend.account;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

@Repository
public class LinkedAccountRepository {

    private final JdbcTemplate jdbc;

    public LinkedAccountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The provider's reference for where this user receives money, if they have a usable account. */
    public Optional<String> activeReceivingRef(long userId, String provider, Instant now) {
        return jdbc.queryForList("""
                SELECT receiving_ref FROM linked_accounts
                WHERE user_id = ? AND provider = ? AND status = 'ACTIVE' AND receiving_ref IS NOT NULL
                  AND (expires_at IS NULL OR expires_at > ?)
                ORDER BY created_at DESC
                LIMIT 1
                """, String.class, userId, provider, OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .stream().findFirst();
    }
}

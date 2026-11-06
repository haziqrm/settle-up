package com.settleup.backend.group;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.HashSet;
import java.util.Set;

/** Read-only lookups on groups and membership, shared by the expense and balance features. */
@Repository
public class GroupRepository {

    private final JdbcTemplate jdbc;

    public GroupRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean groupExists(long groupId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM groups WHERE id = ?)", Boolean.class, groupId));
    }

    public Set<Long> memberIdsOf(long groupId) {
        return new HashSet<>(jdbc.queryForList(
                "SELECT user_id FROM group_members WHERE group_id = ?", Long.class, groupId));
    }
}

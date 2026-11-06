package com.settleup.backend.dev;

import com.settleup.backend.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration.class)
class DevSeedIntegrationTest {

    @Autowired DevSeed seed;
    @Autowired JdbcTemplate jdbc;

    @Test
    void seedRanAtStartupAndIsIdempotent() {
        assertSeededOnce();

        // Simulates a restart against the same database.
        seed.run(new DefaultApplicationArguments());

        assertSeededOnce();
    }

    private void assertSeededOnce() {
        List<Long> groupIds = jdbc.queryForList("SELECT id FROM groups WHERE name = ?", Long.class, DevSeed.GROUP_NAME);
        assertEquals(1, groupIds.size());

        List<String> members = jdbc.queryForList("""
                SELECT u.display_name FROM group_members m JOIN users u ON u.id = m.user_id
                WHERE m.group_id = ? ORDER BY u.id
                """, String.class, groupIds.getFirst());
        assertEquals(DevSeed.USERS, members);

        String ownerName = jdbc.queryForObject("""
                SELECT u.display_name FROM group_members m JOIN users u ON u.id = m.user_id
                WHERE m.group_id = ? AND m.role = 'OWNER'
                """, String.class, groupIds.getFirst());
        assertEquals("Alex", ownerName);

        int linked = jdbc.queryForObject("""
                SELECT COUNT(*) FROM linked_accounts la JOIN group_members m ON m.user_id = la.user_id
                WHERE m.group_id = ? AND la.provider = 'FAKE' AND la.status = 'ACTIVE'
                """, Integer.class, groupIds.getFirst());
        assertEquals(DevSeed.USERS.size(), linked);
    }
}

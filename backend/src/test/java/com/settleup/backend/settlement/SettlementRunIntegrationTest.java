package com.settleup.backend.settlement;

import com.settleup.backend.TestcontainersConfiguration;
import com.settleup.backend.ledger.ExpenseService;
import com.settleup.backend.ledger.LedgerFixtures;
import com.settleup.backend.ledger.LedgerFixtures.Group;
import com.settleup.backend.settlement.SettlementExceptions.RunAlreadyOpenException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SettlementRunIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ExpenseService expenses;
    @Autowired SettlementRunService runService;
    @Autowired SettlementRunRepository runRepository;

    LedgerFixtures fx;
    Group g;
    long a, b, c;

    @BeforeEach
    void setUp() {
        fx = new LedgerFixtures(jdbc);
        g = fx.newGroupWithMembers(3);
        a = g.memberIds().get(0);
        b = g.memberIds().get(1);
        c = g.memberIds().get(2);
    }

    private ResultActions createRun(Object groupId, String body) throws Exception {
        return mvc.perform(post("/groups/{groupId}/settlement-runs", groupId)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions createRunBy(long userId) throws Exception {
        return createRun(g.id(), "{\"createdBy\": %d}".formatted(userId));
    }

    private int runCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM settlement_runs WHERE group_id = ?", Integer.class, g.id());
    }

    private int paymentCount() {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM payments p JOIN settlement_runs r ON r.id = p.run_id WHERE r.group_id = ?
                """, Integer.class, g.id());
    }

    // --- creating a run ---------------------------------------------------------------

    @Test
    void createsOpenRunWithOnePlannedPaymentPerTransfer() throws Exception {
        expenses.addExpense(g.id(), a, List.of(a, b, c), 3000, "Groceries");
        int ledgerRowsBefore = fx.ledgerRowCount(g.id());
        Instant before = Instant.now();

        String body = createRunBy(b)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.groupId").value(g.id()))
                .andExpect(jsonPath("$.createdBy").value(b))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.payments", hasSize(2)))
                .andExpect(jsonPath("$.payments[*].status", everyItem(is("PLANNED"))))
                .andExpect(jsonPath("$.payments[0].payerId").value(b))
                .andExpect(jsonPath("$.payments[0].payeeId").value(a))
                .andExpect(jsonPath("$.payments[0].amountPence").value(1000))
                .andExpect(jsonPath("$.payments[1].payerId").value(c))
                .andExpect(jsonPath("$.payments[1].payeeId").value(a))
                .andExpect(jsonPath("$.payments[1].amountPence").value(1000))
                .andReturn().getResponse().getContentAsString();

        long runId = runIdFrom(body);
        var view = runService.getRun(runId);
        assertEquals("run-%d-%d-%d".formatted(runId, b, a), view.payments().get(0).idempotencyKey());
        assertEquals("run-%d-%d-%d".formatted(runId, c, a), view.payments().get(1).idempotencyKey());

        Instant deadline = view.run().deadlineAt();
        assertFalse(deadline.isBefore(before.plus(Duration.ofHours(72)).minusSeconds(1)), "deadline " + deadline);
        assertFalse(deadline.isAfter(Instant.now().plus(Duration.ofHours(72))), "deadline " + deadline);

        // Creating a run plans payments; it moves no money, so the ledger is untouched.
        assertEquals(ledgerRowsBefore, fx.ledgerRowCount(g.id()));
    }

    @Test
    void getRunReturnsTheSameRun() throws Exception {
        expenses.addExpense(g.id(), a, List.of(b), 500, "Lunch");
        long runId = runService.createRun(g.id(), a);

        mvc.perform(get("/settlement-runs/{runId}", runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value(runId))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.payments", hasSize(1)))
                .andExpect(jsonPath("$.payments[0].payerName").value("Member 1"))
                .andExpect(jsonPath("$.payments[0].payeeName").value("Member 0"))
                .andExpect(jsonPath("$.payments[0].amountPence").value(500));
    }

    // --- one open run per group -------------------------------------------------------

    @Test
    void secondRunWhileOneIsOpenIs409NamingTheOpenRun() throws Exception {
        expenses.addExpense(g.id(), a, List.of(b), 500, "Lunch");
        long first = runService.createRun(g.id(), a);

        createRunBy(b)
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail", endsWith(": " + first)));

        assertEquals(1, runCount());
        assertEquals(1, paymentCount());
    }

    @Test
    void databaseIndexRejectsASecondOpenRunEvenWithoutTheServiceCheck() {
        runRepository.insertRun(g.id(), a, Instant.now().plusSeconds(60));

        DuplicateKeyException e = assertThrows(DuplicateKeyException.class,
                () -> runRepository.insertRun(g.id(), b, Instant.now().plusSeconds(60)));
        // SettlementRunService relies on the index name appearing in the message.
        assertTrue(e.getMessage().contains("uq_one_open_run_per_group"), e.getMessage());
    }

    @Test
    void concurrentCreatesProduceExactlyOneRun() throws Exception {
        expenses.addExpense(g.id(), a, List.of(a, b, c), 3000, "Groceries");
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Long>> results = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                long creator = g.memberIds().get(i % 3);
                results.add(pool.submit(() -> {
                    start.await();
                    return runService.createRun(g.id(), creator);
                }));
            }
            start.countDown();

            int created = 0, conflicts = 0;
            for (Future<Long> f : results) {
                try {
                    f.get(30, TimeUnit.SECONDS);
                    created++;
                } catch (java.util.concurrent.ExecutionException e) {
                    assertInstanceOf(RunAlreadyOpenException.class, e.getCause());
                    conflicts++;
                }
            }
            assertEquals(1, created);
            assertEquals(threads - 1, conflicts);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, runCount());
        assertEquals(2, paymentCount());
    }

    // --- rejected requests ------------------------------------------------------------

    @Test
    void settledGroupIs409AndCreatesNothing() throws Exception {
        createRunBy(a)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Nothing to settle"));
        assertEquals(0, runCount());
    }

    @Test
    void nonMemberCreatorIs400() throws Exception {
        expenses.addExpense(g.id(), a, List.of(b), 500, "Lunch");
        long outsider = fx.newUser("Outsider");

        createRunBy(outsider)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("user %d is not a member of group %d".formatted(outsider, g.id())));
        assertEquals(0, runCount());
    }

    @Test
    void unknownGroupOrRunIs404() throws Exception {
        createRun(Long.MAX_VALUE, "{\"createdBy\": %d}".formatted(a)).andExpect(status().isNotFound());
        mvc.perform(get("/settlement-runs/{runId}", Long.MAX_VALUE))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Settlement run not found"));
    }

    @Test
    void missingCreatorIs400() throws Exception {
        createRun(g.id(), "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("createdBy")));
    }

    private static long runIdFrom(String json) {
        var m = java.util.regex.Pattern.compile("\"runId\":(\\d+)").matcher(json);
        assertTrue(m.find(), json);
        return Long.parseLong(m.group(1));
    }
}

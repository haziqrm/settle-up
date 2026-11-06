package com.settleup.backend.settlement;

import com.settleup.backend.TestcontainersConfiguration;
import com.settleup.backend.ledger.ExpenseService;
import com.settleup.backend.ledger.LedgerFixtures;
import com.settleup.backend.ledger.LedgerFixtures.Group;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SettlementPlanApiIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ExpenseService expenses;

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

    @Test
    void groupWithNoExpensesHasNothingToSettle() throws Exception {
        mvc.perform(get("/groups/{groupId}/settlement-plan", g.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groupId").value(g.id()))
                .andExpect(jsonPath("$.transfers", hasSize(0)));
    }

    @Test
    void plansTransfersFromLedgerBalances() throws Exception {
        expenses.addExpense(g.id(), a, List.of(a, b, c), 3000, "Groceries");

        mvc.perform(get("/groups/{groupId}/settlement-plan", g.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transfers", hasSize(2)))
                .andExpect(jsonPath("$.transfers[0].payerId").value(b))
                .andExpect(jsonPath("$.transfers[0].payerName").value("Member 1"))
                .andExpect(jsonPath("$.transfers[0].payeeId").value(a))
                .andExpect(jsonPath("$.transfers[0].payeeName").value("Member 0"))
                .andExpect(jsonPath("$.transfers[0].amountPence").value(1000))
                .andExpect(jsonPath("$.transfers[1].payerId").value(c))
                .andExpect(jsonPath("$.transfers[1].payeeId").value(a))
                .andExpect(jsonPath("$.transfers[1].amountPence").value(1000));
    }

    @Test
    void crossingDebtsAreNettedIntoOneTransfer() throws Exception {
        // a owes b 500 and b owes a 300: net is a pays b 200.
        expenses.addExpense(g.id(), b, List.of(a), 500, "Cinema");
        expenses.addExpense(g.id(), a, List.of(b), 300, "Coffee");

        mvc.perform(get("/groups/{groupId}/settlement-plan", g.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transfers", hasSize(1)))
                .andExpect(jsonPath("$.transfers[0].payerId").value(a))
                .andExpect(jsonPath("$.transfers[0].payeeId").value(b))
                .andExpect(jsonPath("$.transfers[0].amountPence").value(200));
    }

    @Test
    void previewWritesNothing() throws Exception {
        expenses.addExpense(g.id(), a, List.of(a, b, c), 1000, "Dinner");
        int rowsBefore = fx.ledgerRowCount(g.id());
        int runsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM settlement_runs", Integer.class);
        int paymentsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM payments", Integer.class);

        mvc.perform(get("/groups/{groupId}/settlement-plan", g.id())).andExpect(status().isOk());
        mvc.perform(get("/groups/{groupId}/settlement-plan", g.id())).andExpect(status().isOk());

        assertEquals(rowsBefore, fx.ledgerRowCount(g.id()));
        assertEquals(runsBefore, jdbc.queryForObject("SELECT COUNT(*) FROM settlement_runs", Integer.class));
        assertEquals(paymentsBefore, jdbc.queryForObject("SELECT COUNT(*) FROM payments", Integer.class));
    }

    @Test
    void unknownGroupIs404() throws Exception {
        mvc.perform(get("/groups/{groupId}/settlement-plan", Long.MAX_VALUE))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("group " + Long.MAX_VALUE + " not found"));
    }

    @Test
    void nonNumericGroupIdIs400() throws Exception {
        mvc.perform(get("/groups/{groupId}/settlement-plan", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }
}

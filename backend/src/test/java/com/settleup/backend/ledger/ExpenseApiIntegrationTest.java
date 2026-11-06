package com.settleup.backend.ledger;

import com.settleup.backend.TestcontainersConfiguration;
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
import org.springframework.test.web.servlet.ResultActions;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** The HTTP layer end to end: JSON in, status codes and problem+json out, real Postgres. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ExpenseApiIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    LedgerFixtures fx;
    Group g;
    long a, b, c;

    @BeforeEach
    void setUp() {
        fx = new LedgerFixtures(jdbc);
        g = fx.newGroupWithMembers(4);
        a = g.memberIds().get(0);
        b = g.memberIds().get(1);
        c = g.memberIds().get(2);
    }

    private ResultActions postExpense(Object groupId, String json) throws Exception {
        return mvc.perform(post("/groups/{groupId}/expenses", groupId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    private String expense(long paidBy, String participants, String totalPence) {
        return """
                {"paidBy": %d, "participantIds": %s, "totalPence": %s, "description": "Dinner"}
                """.formatted(paidBy, participants, totalPence);
    }

    /** Every error must be problem+json with no Java internals leaking out. */
    private static void assertProblem(ResultActions r, int status) throws Exception {
        r.andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(status))
                .andExpect(content().string(not(containsString("Exception"))))
                .andExpect(content().string(not(containsString("at com."))));
    }

    // --- happy path -------------------------------------------------------------------

    @Test
    void postExpenseThenGetBalances() throws Exception {
        postExpense(g.id(), expense(a, "[%d, %d, %d]".formatted(a, b, c), "1000"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.expenseId", isA(Number.class)));

        mvc.perform(get("/groups/{groupId}/balances", g.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groupId").value(g.id()))
                .andExpect(jsonPath("$.balances", hasSize(4)))
                .andExpect(jsonPath("$.balances[0].userId").value(a))
                .andExpect(jsonPath("$.balances[0].balancePence").value(666))
                .andExpect(jsonPath("$.balances[1].balancePence").value(-333))
                .andExpect(jsonPath("$.balances[2].balancePence").value(-333))
                // The fourth member took no part and still appears, with 0.
                .andExpect(jsonPath("$.balances[3].balancePence").value(0));

        assertEquals(0, fx.ledgerSum(g.id()));
    }

    // --- 404 --------------------------------------------------------------------------

    @Test
    void unknownGroupIs404OnBothEndpoints() throws Exception {
        assertProblem(postExpense(Long.MAX_VALUE, expense(a, "[%d]".formatted(a), "100")), 404);
        assertProblem(mvc.perform(get("/groups/{groupId}/balances", Long.MAX_VALUE)), 404);
    }

    // --- 400: business rules ----------------------------------------------------------

    @Test
    void zeroOrNegativeAmountIs400() throws Exception {
        assertProblem(postExpense(g.id(), expense(a, "[%d]".formatted(b), "0")), 400);
        assertProblem(postExpense(g.id(), expense(a, "[%d]".formatted(b), "-100")), 400);
        assertEquals(0, fx.expenseCount(g.id()));
    }

    @Test
    void nonMemberPayerOrParticipantIs400WithAClearMessage() throws Exception {
        long outsider = fx.newUser("Outsider");

        ResultActions payer = postExpense(g.id(), expense(outsider, "[%d]".formatted(a), "100"));
        assertProblem(payer, 400);
        payer.andExpect(jsonPath("$.detail").value("payer " + outsider + " is not a member of this group"));

        ResultActions participant = postExpense(g.id(), expense(a, "[%d, %d]".formatted(b, outsider), "100"));
        assertProblem(participant, 400);
        participant.andExpect(jsonPath("$.detail", containsString(String.valueOf(outsider))));

        assertEquals(0, fx.expenseCount(g.id()));
    }

    // --- 400: malformed requests ------------------------------------------------------

    @Test
    void fractionalPenceIs400NotTruncated() throws Exception {
        assertProblem(postExpense(g.id(), expense(a, "[%d]".formatted(b), "10.5")), 400);
        assertEquals(0, fx.expenseCount(g.id()));
    }

    @Test
    void missingRequiredFieldsAre400AndNamed() throws Exception {
        ResultActions r = postExpense(g.id(), """
                {"participantIds": [%d], "description": "No payer or amount"}
                """.formatted(a));
        assertProblem(r, 400);
        r.andExpect(jsonPath("$.detail", allOf(containsString("paidBy"), containsString("totalPence"))));
    }

    @Test
    void malformedJsonIs400() throws Exception {
        assertProblem(postExpense(g.id(), "{not json"), 400);
    }

    @Test
    void nonNumericGroupIdIs400() throws Exception {
        assertProblem(postExpense("abc", expense(a, "[%d]".formatted(a), "100")), 400);
    }
}

package com.settleup.backend.settlement;

import com.settleup.backend.TestcontainersConfiguration;
import com.settleup.backend.ledger.ExpenseService;
import com.settleup.backend.ledger.LedgerFixtures;
import com.settleup.backend.ledger.LedgerFixtures.Group;
import com.settleup.backend.settlement.PayService.PayResult;
import com.settleup.backend.settlement.SettlementRunRepository.PaymentRow;
import com.settleup.backend.settlement.provider.InProcessFakeProvider;
import com.settleup.backend.settlement.provider.PaymentProvider.CreatePaymentRequest;
import org.junit.jupiter.api.AfterEach;
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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PayIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ExpenseService expenses;
    @Autowired SettlementRunService runs;
    @Autowired PaymentTransitionService transitions;
    @Autowired PayService payService;
    @Autowired InProcessFakeProvider fakeProvider;

    LedgerFixtures fx;
    Group g;
    long a, b, c, runId;
    PaymentRow bPaysA;

    /** a paid £30 for three; b owes a 1000 and c owes a 1000. Only a (the receiver) has a linked account. */
    @BeforeEach
    void setUp() {
        fx = new LedgerFixtures(jdbc);
        g = fx.newGroupWithMembers(3);
        a = g.memberIds().get(0);
        b = g.memberIds().get(1);
        c = g.memberIds().get(2);
        expenses.addExpense(g.id(), a, List.of(a, b, c), 3000, "Groceries");
        runId = runs.createRun(g.id(), a);
        bPaysA = runs.getRun(runId).payments().getFirst();
        linkAccount(a, "ACTIVE", null);
    }

    @AfterEach
    void providerBackUp() {
        fakeProvider.setAvailable(true);
    }

    private void linkAccount(long userId, String status, String expiresAtSql) {
        jdbc.update("""
                INSERT INTO linked_accounts (user_id, provider, receiving_ref, display_label, status, expires_at)
                VALUES (?, 'FAKE', ?, 'Test account', ?, %s)
                """.formatted(expiresAtSql == null ? "NULL" : expiresAtSql), userId, "acct-" + userId, status);
    }

    private ResultActions pay(long paymentId, long payerId) throws Exception {
        return mvc.perform(post("/payments/{paymentId}/pay", paymentId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"payerId\": %d}".formatted(payerId)));
    }

    private Map<String, Object> paymentRow(long paymentId) {
        return jdbc.queryForMap("SELECT status, provider, provider_payment_id, auth_expires_at FROM payments WHERE id = ?",
                paymentId);
    }

    // --- happy path -------------------------------------------------------------------

    @Test
    void payStartsAuthorisationAndReturnsTheBankUrl() throws Exception {
        pay(bPaysA.id(), b)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentId").value(bPaysA.id()))
                .andExpect(jsonPath("$.status").value("AWAITING_AUTHORISATION"))
                .andExpect(jsonPath("$.provider").value("FAKE"))
                .andExpect(jsonPath("$.providerPaymentId", startsWith("fake-")))
                .andExpect(jsonPath("$.authorisationUrl", startsWith("https://fake-bank.invalid/authorise/fake-")))
                .andExpect(jsonPath("$.authExpiresAt").exists());

        Map<String, Object> row = paymentRow(bPaysA.id());
        assertEquals("AWAITING_AUTHORISATION", row.get("status"));
        assertEquals("FAKE", row.get("provider"));
        assertNotNull(row.get("provider_payment_id"));
        assertNotNull(row.get("auth_expires_at"));
        // Starting a payment moves no money.
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ledger_entries WHERE payment_id = ?", Integer.class, bPaysA.id()));
    }

    // --- retries, double taps, crashes ------------------------------------------------

    @Test
    void payingTwiceReturnsTheSamePayment() {
        PayResult first = payService.pay(bPaysA.id(), b);
        PayResult second = payService.pay(bPaysA.id(), b);

        assertEquals(first.providerPaymentId(), second.providerPaymentId());
        assertEquals(first.authorisationUrl(), second.authorisationUrl());
        assertEquals(1, fakeProvider.paymentsFor(bPaysA.idempotencyKey()));
    }

    @Test
    void concurrentDoubleTapCreatesOneProviderPayment() throws Exception {
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<PayResult>> results = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return payService.pay(bPaysA.id(), b);
                }));
            }
            start.countDown();
            Set<String> providerIds = new HashSet<>();
            for (Future<PayResult> f : results) providerIds.add(f.get(30, TimeUnit.SECONDS).providerPaymentId());
            assertEquals(1, providerIds.size());
            assertEquals(providerIds.iterator().next(), paymentRow(bPaysA.id()).get("provider_payment_id"));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void crashAfterProviderCallIsRecoveredByPayingAgain() {
        // Simulate a crash: the provider created the payment, but we never recorded it.
        String orphanId = fakeProvider.createPayment(new CreatePaymentRequest(
                bPaysA.idempotencyKey(), bPaysA.amountPence(), "acct-" + a, "ref")).providerPaymentId();
        assertEquals("PLANNED", paymentRow(bPaysA.id()).get("status"));

        PayResult retried = payService.pay(bPaysA.id(), b);

        assertEquals(orphanId, retried.providerPaymentId());
        assertEquals(orphanId, paymentRow(bPaysA.id()).get("provider_payment_id"));
        assertEquals(1, fakeProvider.paymentsFor(bPaysA.idempotencyKey()));
    }

    @Test
    void providerOutageIs502AndCanBeRetried() throws Exception {
        fakeProvider.setAvailable(false);
        pay(bPaysA.id(), b)
                .andExpect(status().isBadGateway())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail", containsString("try again")));
        assertEquals("PLANNED", paymentRow(bPaysA.id()).get("status"));

        fakeProvider.setAvailable(true);
        pay(bPaysA.id(), b).andExpect(status().isOk());
        assertEquals("AWAITING_AUTHORISATION", paymentRow(bPaysA.id()).get("status"));
    }

    // --- rejected ---------------------------------------------------------------------

    @Test
    void onlyThePayerCanPay() throws Exception {
        pay(bPaysA.id(), c)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("user %d is not the payer of payment %d".formatted(c, bPaysA.id())));
        assertEquals("PLANNED", paymentRow(bPaysA.id()).get("status"));
    }

    @Test
    void receiverWithoutLinkedAccountBlocksPayWithAClearMessage() throws Exception {
        jdbc.update("UPDATE linked_accounts SET status = 'REVOKED' WHERE user_id = ?", a);

        pay(bPaysA.id(), b)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Receiver has no linked account"))
                .andExpect(jsonPath("$.detail", containsString("has not linked a bank account")));
        assertEquals("PLANNED", paymentRow(bPaysA.id()).get("status"));
        assertEquals(0, fakeProvider.paymentsFor(bPaysA.idempotencyKey()));
    }

    @Test
    void expiredLinkedAccountDoesNotCount() throws Exception {
        jdbc.update("UPDATE linked_accounts SET status = 'REVOKED' WHERE user_id = ?", a);
        linkAccount(a, "ACTIVE", "now() - interval '1 day'");

        pay(bPaysA.id(), b).andExpect(status().isConflict());
    }

    @Test
    void cannotPayAfterTheDeadline() throws Exception {
        jdbc.update("UPDATE settlement_runs SET deadline_at = now() - interval '1 minute' WHERE id = ?", runId);
        pay(bPaysA.id(), b)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("deadline has passed")));
    }

    @Test
    void cannotPayInAClosedRun() throws Exception {
        jdbc.update("UPDATE settlement_runs SET status = 'CANCELLED' WHERE id = ?", runId);
        pay(bPaysA.id(), b)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("CANCELLED")));
    }

    @Test
    void cannotPayAFinishedPayment() throws Exception {
        payService.pay(bPaysA.id(), b);
        transitions.moveTo(bPaysA.id(), PaymentStatus.FAILED);

        pay(bPaysA.id(), b)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("already FAILED")));
    }

    @Test
    void unknownPaymentIs404AndMissingPayerIs400() throws Exception {
        pay(Long.MAX_VALUE, b).andExpect(status().isNotFound());
        mvc.perform(post("/payments/{paymentId}/pay", bPaysA.id()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("payerId")));
    }
}

package com.settleup.backend.settlement;

import com.settleup.backend.settlement.Netting.Transfer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class NettingTest {

    private static final long ALEX = 1, BEN = 2, CARA = 3, DAN = 4;

    // --- examples ---------------------------------------------------------------------

    @Test
    void readmeExampleBenAndCaraEachPayAlexTenPounds() {
        assertEquals(List.of(new Transfer(BEN, ALEX, 1000), new Transfer(CARA, ALEX, 1000)),
                Netting.plan(Map.of(ALEX, 2000L, BEN, -1000L, CARA, -1000L)));
    }

    @Test
    void twoPeopleNeedOneTransfer() {
        assertEquals(List.of(new Transfer(BEN, ALEX, 450)), Netting.plan(Map.of(ALEX, 450L, BEN, -450L)));
    }

    @Test
    void oneDebtorPaysSeveralCreditorsLargestFirst() {
        assertEquals(List.of(
                        new Transfer(DAN, CARA, 500),
                        new Transfer(DAN, ALEX, 300),
                        new Transfer(DAN, BEN, 300)),
                Netting.plan(Map.of(ALEX, 300L, BEN, 300L, CARA, 500L, DAN, -1100L)));
    }

    @Test
    void largestDebtorIsMatchedWithLargestCreditor() {
        assertEquals(List.of(
                        new Transfer(CARA, ALEX, 700),
                        new Transfer(DAN, BEN, 300),
                        new Transfer(DAN, ALEX, 100)),
                Netting.plan(Map.of(ALEX, 800L, BEN, 300L, CARA, -700L, DAN, -400L)));
    }

    @Test
    void settledOrEmptyGroupNeedsNoTransfers() {
        assertTrue(Netting.plan(Map.of()).isEmpty());
        assertTrue(Netting.plan(Map.of(ALEX, 0L, BEN, 0L)).isEmpty());
    }

    @Test
    void rejectsBalancesThatDoNotSumToZero() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Netting.plan(Map.of(ALEX, 100L, BEN, -99L)));
        assertTrue(e.getMessage().contains("sum to zero"));
    }

    @Test
    void rejectsNullsAndOverflow() {
        Map<Long, Long> withNull = new HashMap<>();
        withNull.put(ALEX, null);
        assertThrows(IllegalArgumentException.class, () -> Netting.plan(withNull));
        assertThrows(ArithmeticException.class,
                () -> Netting.plan(Map.of(ALEX, Long.MAX_VALUE, BEN, 1L, CARA, Long.MIN_VALUE)));
    }

    // --- properties over random groups ------------------------------------------------

    @Test
    void randomGroupsSatisfyEveryInvariant() {
        Random random = new Random(42); // fixed seed: a failure is reproducible
        for (int trial = 0; trial < 5_000; trial++) {
            Map<Long, Long> balances = randomZeroSumBalances(random, 1 + random.nextInt(12));
            List<Transfer> transfers = Netting.plan(balances);
            String context = "balances=" + balances + " transfers=" + transfers;

            // Applying the transfers settles everyone exactly.
            Map<Long, Long> after = new HashMap<>(balances);
            for (Transfer t : transfers) {
                after.merge(t.payerId(), t.amountPence(), Long::sum);
                after.merge(t.payeeId(), -t.amountPence(), Long::sum);
            }
            after.values().forEach(v -> assertEquals(0, v, context));

            long nonZero = balances.values().stream().filter(v -> v != 0).count();
            assertTrue(transfers.size() <= Math.max(0, nonZero - 1), "too many transfers: " + context);

            Set<Long> payers = new HashSet<>(), payees = new HashSet<>();
            for (Transfer t : transfers) {
                assertTrue(t.amountPence() > 0, context);
                assertNotEquals(t.payerId(), t.payeeId(), context);
                assertTrue(balances.get(t.payerId()) < 0, "payer was not in debt: " + context);
                assertTrue(balances.get(t.payeeId()) > 0, "payee was not owed: " + context);
                payers.add(t.payerId());
                payees.add(t.payeeId());
            }
            assertTrue(Collections.disjoint(payers, payees), "someone both pays and receives: " + context);

            assertEquals(transfers, Netting.plan(shuffled(balances, random)), "plan depends on input order");
        }
    }

    private static Map<Long, Long> randomZeroSumBalances(Random random, int people) {
        Map<Long, Long> balances = new HashMap<>();
        long sum = 0;
        for (long id = 1; id < people; id++) {
            long b = random.nextInt(4) == 0 ? 0 : random.nextLong(-10_000, 10_001);
            balances.put(id, b);
            sum += b;
        }
        balances.put((long) people, -sum);
        return balances;
    }

    private static Map<Long, Long> shuffled(Map<Long, Long> balances, Random random) {
        List<Long> ids = new ArrayList<>(balances.keySet());
        Collections.shuffle(ids, random);
        Map<Long, Long> result = new LinkedHashMap<>();
        ids.forEach(id -> result.put(id, balances.get(id)));
        return result;
    }
}

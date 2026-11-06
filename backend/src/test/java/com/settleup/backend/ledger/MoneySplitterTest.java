package com.settleup.backend.ledger;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MoneySplitterTest {

    @Test
    void sharesAlwaysSumToTotal() {
        for (long total = 1; total <= 5000; total++) {
            for (int people = 1; people <= 10; people++) {
                List<Long> shares = MoneySplitter.splitEqually(total, people);
                long sum = shares.stream().mapToLong(Long::longValue).sum();
                assertEquals(total, sum, "total=" + total + " people=" + people);
            }
        }
    }

    @Test
    void tenPoundsThreeWaysGivesExtraPennyToFirstPerson() {
        assertEquals(List.of(334L, 333L, 333L), MoneySplitter.splitEqually(1000, 3));
    }
}
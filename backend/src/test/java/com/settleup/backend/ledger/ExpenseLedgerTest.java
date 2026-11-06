package com.settleup.backend.ledger;

import com.settleup.backend.ledger.ExpenseLedger.LedgerLine;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExpenseLedgerTest {

    private static long sum(List<LedgerLine> lines) {
        return lines.stream().mapToLong(LedgerLine::amountPence).sum();
    }

    @Test
    void thirtyPoundDinnerSplitThreeWays() {
        List<LedgerLine> lines = ExpenseLedger.entriesFor(1, List.of(1L, 2L, 3L), 3000);
        assertEquals(List.of(
                new LedgerLine(1, 2000),
                new LedgerLine(2, -1000),
                new LedgerLine(3, -1000)), lines);
    }

    @Test
    void linesAlwaysSumToZero() {
        for (long total = 1; total <= 500; total++) {
            for (int n = 1; n <= 6; n++) {
                List<Long> participants = new ArrayList<>();
                for (long id = 1; id <= n; id++) participants.add(id);
                for (long payer = 1; payer <= n + 1; payer++) {
                    assertEquals(0, sum(ExpenseLedger.entriesFor(payer, participants, total)),
                            "total=" + total + " n=" + n + " payer=" + payer);
                }
            }
        }
    }

    @Test
    void payerNotSplittingIsOwedTheFullAmount() {
        List<LedgerLine> lines = ExpenseLedger.entriesFor(9, List.of(1L, 2L), 1000);
        assertEquals(1000, lines.stream().filter(l -> l.userId() == 9).findFirst().get().amountPence());
        assertEquals(0, sum(lines));
    }

    @Test
    void payerSplittingOnlyWithThemselvesProducesNoLines() {
        assertTrue(ExpenseLedger.entriesFor(1, List.of(1L), 500).isEmpty());
    }

    @Test
    void rejectsBadInput() {
        assertThrows(IllegalArgumentException.class, () -> ExpenseLedger.entriesFor(1, List.of(1L, 1L), 100));
        assertThrows(IllegalArgumentException.class, () -> ExpenseLedger.entriesFor(1, List.of(), 100));
        assertThrows(IllegalArgumentException.class, () -> ExpenseLedger.entriesFor(1, List.of(1L), 0));
    }
}
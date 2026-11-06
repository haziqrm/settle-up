package com.settleup.backend.ledger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ExpenseLedger {

    public record LedgerLine(long userId, long amountPence) {}

    public static List<LedgerLine> entriesFor(long payerId, List<Long> participantIds, long totalPence) {
        if (totalPence <= 0) throw new IllegalArgumentException("total must be > 0");
        if (participantIds.isEmpty()) throw new IllegalArgumentException("need at least one participant");
        if (new HashSet<>(participantIds).size() != participantIds.size())
            throw new IllegalArgumentException("duplicate participants");

        // Sort so the extra penny always goes to the same person, whatever order the caller used.
        List<Long> sorted = participantIds.stream().sorted().toList();
        List<Long> shares = MoneySplitter.splitEqually(totalPence, sorted.size());

        Map<Long, Long> net = new LinkedHashMap<>();
        for (int i = 0; i < sorted.size(); i++) {
            net.merge(sorted.get(i), -shares.get(i), Long::sum);
        }
        net.merge(payerId, totalPence, Long::sum);

        List<LedgerLine> lines = new ArrayList<>();
        net.forEach((userId, amount) -> {
            if (amount != 0) lines.add(new LedgerLine(userId, amount));
        });
        return lines;
    }
}
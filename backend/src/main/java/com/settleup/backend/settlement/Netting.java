package com.settleup.backend.settlement;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

public final class Netting {

    public record Transfer(long payerId, long payeeId, long amountPence) {}

    private record Party(long userId, long pence) {}

    // Largest amount first; ties broken by user id so the same balances always give the same plan.
    private static final Comparator<Party> LARGEST_FIRST =
            Comparator.comparingLong(Party::pence).reversed().thenComparingLong(Party::userId);

    private Netting() {}

    /**
     * Greedy netting: the person who owes most pays the person owed most, until all are zero.
     * Each step clears at least one person, so n non-zero balances need at most n - 1 transfers.
     *
     * @param balancesPence user id to balance; positive is owed money, negative owes money
     */
    public static List<Transfer> plan(Map<Long, Long> balancesPence) {
        PriorityQueue<Party> debtors = new PriorityQueue<>(LARGEST_FIRST);
        PriorityQueue<Party> creditors = new PriorityQueue<>(LARGEST_FIRST);
        long sum = 0;

        for (Map.Entry<Long, Long> e : balancesPence.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) {
                throw new IllegalArgumentException("user ids and balances must not be null");
            }
            long balance = e.getValue();
            sum = Math.addExact(sum, balance);
            if (balance < 0) debtors.add(new Party(e.getKey(), Math.negateExact(balance)));
            else if (balance > 0) creditors.add(new Party(e.getKey(), balance));
        }
        if (sum != 0) {
            throw new IllegalArgumentException("balances must sum to zero, got " + sum);
        }

        List<Transfer> transfers = new ArrayList<>();
        while (!debtors.isEmpty()) {
            Party debtor = debtors.poll();
            Party creditor = creditors.poll();
            long amount = Math.min(debtor.pence(), creditor.pence());
            transfers.add(new Transfer(debtor.userId(), creditor.userId(), amount));

            if (debtor.pence() > amount) debtors.add(new Party(debtor.userId(), debtor.pence() - amount));
            if (creditor.pence() > amount) creditors.add(new Party(creditor.userId(), creditor.pence() - amount));
        }
        return transfers;
    }
}

package com.settleup.backend.ledger;

import java.util.ArrayList;
import java.util.List;

public final class MoneySplitter {
    public static List<Long> splitEqually(long totalPence, int people) {
        if (people <= 0) throw new IllegalArgumentException("people must be > 0");
        long base = totalPence / people;
        int remainder = (int) (totalPence % people);
        List<Long> shares = new ArrayList<>();
        for (int i = 0; i < people; i++) {
            shares.add(base + (i < remainder ? 1 : 0));
        }
        return shares;
    }
}
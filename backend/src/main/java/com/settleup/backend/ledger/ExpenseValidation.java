package com.settleup.backend.ledger;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Pure checks on an expense before anything touches the database. The caller loads the
 * group's member ids; this class only works on plain values, so it is unit-testable
 * without Spring or Postgres.
 */
public final class ExpenseValidation {

    private ExpenseValidation() {}

    public static void check(Set<Long> memberIds, long paidBy, List<Long> participantIds,
                             long totalPence, String description) {
        if (totalPence <= 0) {
            throw new InvalidExpenseException("totalPence must be greater than 0");
        }
        if (description == null || description.isBlank()) {
            throw new InvalidExpenseException("description is required");
        }
        if (participantIds == null || participantIds.isEmpty()) {
            throw new InvalidExpenseException("at least one participant is required");
        }
        // Not contains(null): List.of(...) throws NullPointerException for that query.
        if (participantIds.stream().anyMatch(Objects::isNull)) {
            throw new InvalidExpenseException("participant ids must not be null");
        }
        if (new HashSet<>(participantIds).size() != participantIds.size()) {
            throw new InvalidExpenseException("participants must not repeat");
        }
        if (!memberIds.contains(paidBy)) {
            throw new InvalidExpenseException("payer " + paidBy + " is not a member of this group");
        }
        // TreeSet so the error lists ids in a stable order.
        Set<Long> outsiders = new TreeSet<>(participantIds);
        outsiders.removeAll(memberIds);
        if (!outsiders.isEmpty()) {
            throw new InvalidExpenseException("participants " + outsiders + " are not members of this group");
        }
    }
}

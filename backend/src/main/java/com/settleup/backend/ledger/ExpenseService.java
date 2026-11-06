package com.settleup.backend.ledger;

import com.settleup.backend.group.GroupNotFoundException;
import com.settleup.backend.group.GroupRepository;
import com.settleup.backend.ledger.ExpenseLedger.LedgerLine;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

@Service
public class ExpenseService {

    private final GroupRepository groups;
    private final ExpenseRepository expenses;

    public ExpenseService(GroupRepository groups, ExpenseRepository expenses) {
        this.groups = groups;
        this.expenses = expenses;
    }

    /**
     * Records an expense and its ledger rows atomically: either both are written or neither.
     * All checks run before the first insert, so a rejected request writes nothing.
     *
     * @return the new expense id
     */
    @Transactional
    public long addExpense(long groupId, long paidBy, List<Long> participantIds,
                           long totalPence, String description) {
        if (!groups.groupExists(groupId)) {
            throw new GroupNotFoundException(groupId);
        }
        Set<Long> members = groups.memberIdsOf(groupId);
        ExpenseValidation.check(members, paidBy, participantIds, totalPence, description);

        List<LedgerLine> lines = ExpenseLedger.entriesFor(paidBy, participantIds, totalPence);
        long expenseId = expenses.insertExpense(groupId, paidBy, description.strip(), totalPence);
        expenses.insertLedgerLines(groupId, expenseId, lines);
        return expenseId;
    }
}

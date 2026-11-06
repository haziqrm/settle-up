package com.settleup.backend.ledger;

import com.settleup.backend.ledger.BalanceRepository.MemberBalance;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class ExpenseController {

    /**
     * Boxed types so a missing field arrives as null and is rejected, instead of silently
     * becoming 0. Only presence is checked here; the business rules (amount > 0, membership,
     * duplicates) live in ExpenseValidation so there is one source of truth for them.
     */
    public record CreateExpenseRequest(
            @NotNull Long paidBy,
            List<Long> participantIds,
            @NotNull Long totalPence,
            String description) {}

    public record CreateExpenseResponse(long expenseId) {}

    public record BalancesResponse(long groupId, List<MemberBalance> balances) {}

    private final ExpenseService expenses;
    private final BalanceService balances;

    public ExpenseController(ExpenseService expenses, BalanceService balances) {
        this.expenses = expenses;
        this.balances = balances;
    }

    @PostMapping("/groups/{groupId}/expenses")
    @ResponseStatus(HttpStatus.CREATED)
    public CreateExpenseResponse addExpense(@PathVariable long groupId,
                                            @Valid @RequestBody CreateExpenseRequest request) {
        long id = expenses.addExpense(groupId, request.paidBy(), request.participantIds(),
                request.totalPence(), request.description());
        return new CreateExpenseResponse(id);
    }

    @GetMapping("/groups/{groupId}/balances")
    public BalancesResponse balances(@PathVariable long groupId) {
        return new BalancesResponse(groupId, balances.balancesFor(groupId));
    }
}

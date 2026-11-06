package com.settleup.backend.settlement;

import com.settleup.backend.ledger.BalanceRepository.MemberBalance;
import com.settleup.backend.ledger.BalanceService;
import com.settleup.backend.settlement.Netting.Transfer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class SettlementPlanService {

    public record PlannedTransfer(long payerId, String payerName, long payeeId, String payeeName, long amountPence) {}

    private final BalanceService balances;

    public SettlementPlanService(BalanceService balances) {
        this.balances = balances;
    }

    /** A dry run of "Settle up": what each member would pay, without writing anything. */
    @Transactional(readOnly = true)
    public List<PlannedTransfer> previewFor(long groupId) {
        Map<Long, Long> pence = new HashMap<>();
        Map<Long, String> names = new HashMap<>();
        for (MemberBalance b : balances.balancesFor(groupId)) {
            pence.put(b.userId(), b.balancePence());
            names.put(b.userId(), b.displayName());
        }
        return Netting.plan(pence).stream()
                .map(t -> named(t, names))
                .toList();
    }

    private static PlannedTransfer named(Transfer t, Map<Long, String> names) {
        return new PlannedTransfer(t.payerId(), names.get(t.payerId()),
                t.payeeId(), names.get(t.payeeId()), t.amountPence());
    }
}

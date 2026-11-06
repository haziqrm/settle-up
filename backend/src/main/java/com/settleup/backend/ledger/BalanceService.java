package com.settleup.backend.ledger;

import com.settleup.backend.group.GroupNotFoundException;
import com.settleup.backend.group.GroupRepository;
import com.settleup.backend.ledger.BalanceRepository.MemberBalance;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class BalanceService {

    private final GroupRepository groups;
    private final BalanceRepository balances;

    public BalanceService(GroupRepository groups, BalanceRepository balances) {
        this.groups = groups;
        this.balances = balances;
    }

    // readOnly: tells the driver and Postgres no writes are expected, and documents intent.
    @Transactional(readOnly = true)
    public List<MemberBalance> balancesFor(long groupId) {
        if (!groups.groupExists(groupId)) {
            throw new GroupNotFoundException(groupId);
        }
        return balances.balancesOf(groupId);
    }
}

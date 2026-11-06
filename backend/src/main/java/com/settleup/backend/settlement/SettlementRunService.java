package com.settleup.backend.settlement;

import com.settleup.backend.group.GroupNotFoundException;
import com.settleup.backend.group.GroupRepository;
import com.settleup.backend.group.NotGroupMemberException;
import com.settleup.backend.settlement.Netting.Transfer;
import com.settleup.backend.settlement.SettlementExceptions.NothingToSettleException;
import com.settleup.backend.settlement.SettlementExceptions.RunAlreadyOpenException;
import com.settleup.backend.settlement.SettlementExceptions.RunNotFoundException;
import com.settleup.backend.settlement.SettlementRunRepository.PaymentRow;
import com.settleup.backend.settlement.SettlementRunRepository.RunRow;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

@Service
public class SettlementRunService {

    public record RunView(RunRow run, List<PaymentRow> payments) {}

    private static final String ONE_OPEN_RUN_INDEX = "uq_one_open_run_per_group";

    private final GroupRepository groups;
    private final SettlementPlanService plans;
    private final SettlementRunRepository runs;
    private final Clock clock;
    private final Duration runDuration;

    public SettlementRunService(GroupRepository groups, SettlementPlanService plans, SettlementRunRepository runs,
                                Clock clock, @Value("${settleup.settlement.run-duration:PT72H}") Duration runDuration) {
        this.groups = groups;
        this.plans = plans;
        this.runs = runs;
        this.clock = clock;
        this.runDuration = runDuration;
    }

    /**
     * Creates an OPEN run and one PLANNED payment per netted transfer, in one transaction.
     * The pre-check gives a friendly error with the existing run id; the partial unique index
     * is what actually guarantees one open run when two requests race.
     */
    @Transactional
    public long createRun(long groupId, long createdBy) {
        if (!groups.groupExists(groupId)) throw new GroupNotFoundException(groupId);
        if (!groups.memberIdsOf(groupId).contains(createdBy)) throw new NotGroupMemberException(groupId, createdBy);

        runs.findOpenRunId(groupId).ifPresent(id -> {
            throw new RunAlreadyOpenException(groupId, id);
        });

        List<Transfer> transfers = plans.previewFor(groupId).stream()
                .map(p -> new Transfer(p.payerId(), p.payeeId(), p.amountPence()))
                .toList();
        if (transfers.isEmpty()) throw new NothingToSettleException(groupId);

        long runId;
        try {
            runId = runs.insertRun(groupId, createdBy, clock.instant().plus(runDuration));
        } catch (DuplicateKeyException e) {
            if (e.getMessage() != null && e.getMessage().contains(ONE_OPEN_RUN_INDEX)) {
                throw new RunAlreadyOpenException(groupId, null);
            }
            throw e;
        }
        runs.insertPlannedPayments(runId, transfers);
        return runId;
    }

    @Transactional(readOnly = true)
    public RunView getRun(long runId) {
        RunRow run = runs.findRun(runId).orElseThrow(() -> new RunNotFoundException(runId));
        return new RunView(run, runs.paymentsOf(runId));
    }
}

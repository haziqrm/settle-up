package com.settleup.backend.settlement;

import com.settleup.backend.settlement.SettlementPlanService.PlannedTransfer;
import com.settleup.backend.settlement.SettlementRunRepository.PaymentRow;
import com.settleup.backend.settlement.SettlementRunService.RunView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
public class SettlementController {

    public record SettlementPlanResponse(long groupId, List<PlannedTransfer> transfers) {}

    // TODO(auth): take createdBy from the logged-in user instead of the body.
    public record CreateRunRequest(@NotNull Long createdBy) {}

    public record RunResponse(long runId, long groupId, long createdBy, String status,
                              Instant deadlineAt, Instant createdAt, List<PaymentRow> payments) {
        static RunResponse of(RunView v) {
            var r = v.run();
            return new RunResponse(r.id(), r.groupId(), r.createdBy(), r.status(),
                    r.deadlineAt(), r.createdAt(), v.payments());
        }
    }

    private final SettlementPlanService plans;
    private final SettlementRunService runs;

    public SettlementController(SettlementPlanService plans, SettlementRunService runs) {
        this.plans = plans;
        this.runs = runs;
    }

    @GetMapping("/groups/{groupId}/settlement-plan")
    public SettlementPlanResponse plan(@PathVariable long groupId) {
        return new SettlementPlanResponse(groupId, plans.previewFor(groupId));
    }

    @PostMapping("/groups/{groupId}/settlement-runs")
    @ResponseStatus(HttpStatus.CREATED)
    public RunResponse createRun(@PathVariable long groupId, @Valid @RequestBody CreateRunRequest request) {
        long runId = runs.createRun(groupId, request.createdBy());
        return RunResponse.of(runs.getRun(runId));
    }

    @GetMapping("/settlement-runs/{runId}")
    public RunResponse getRun(@PathVariable long runId) {
        return RunResponse.of(runs.getRun(runId));
    }
}

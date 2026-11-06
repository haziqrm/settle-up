package com.settleup.backend.settlement;

import com.settleup.backend.settlement.SettlementPlanService.PlannedTransfer;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class SettlementController {

    public record SettlementPlanResponse(long groupId, List<PlannedTransfer> transfers) {}

    private final SettlementPlanService plans;

    public SettlementController(SettlementPlanService plans) {
        this.plans = plans;
    }

    @GetMapping("/groups/{groupId}/settlement-plan")
    public SettlementPlanResponse plan(@PathVariable long groupId) {
        return new SettlementPlanResponse(groupId, plans.previewFor(groupId));
    }
}

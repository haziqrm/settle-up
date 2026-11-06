package com.settleup.backend.settlement;

import com.settleup.backend.settlement.PayService.PayResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PaymentController {

    // TODO(auth): take payerId from the logged-in user instead of the body.
    public record PayRequest(@NotNull Long payerId) {}

    private final PayService payService;

    public PaymentController(PayService payService) {
        this.payService = payService;
    }

    /** Returns the bank URL to send the payer to. Safe to call again: it returns the same payment. */
    @PostMapping("/payments/{paymentId}/pay")
    public PayResult pay(@PathVariable long paymentId, @Valid @RequestBody PayRequest request) {
        return payService.pay(paymentId, request.payerId());
    }
}

package com.settleup.backend.settlement;

import com.settleup.backend.settlement.PaymentRepository.MovedPayment;
import com.settleup.backend.settlement.PaymentRepository.PayablePayment;
import com.settleup.backend.settlement.SettlementExceptions.IllegalPaymentTransitionException;
import com.settleup.backend.settlement.SettlementExceptions.PaymentNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * The only way a payment's status changes. Deliberately not exposed as an HTTP endpoint:
 * statuses are driven by the provider (webhooks and polling), never by a client saying "I paid".
 */
@Service
public class PaymentTransitionService {

    public enum Outcome { APPLIED, ALREADY_IN_STATE }

    private final PaymentRepository payments;

    public PaymentTransitionService(PaymentRepository payments) {
        this.payments = payments;
    }

    /**
     * Moves a payment to {@code target}. Moving to CONFIRMED also writes the two ledger rows,
     * in the same transaction, so a confirmed status without ledger rows (or the reverse) can
     * never be committed. Repeating a move that already happened is a harmless no-op, which is
     * what makes duplicate webhooks safe.
     */
    @Transactional
    public Outcome moveTo(long paymentId, PaymentStatus target) {
        Optional<MovedPayment> moved =
                payments.compareAndSetStatus(paymentId, PaymentStatus.sourcesFor(target), target);

        if (moved.isPresent()) {
            if (target == PaymentStatus.CONFIRMED) {
                payments.insertLedgerRows(paymentId, moved.get());
            }
            return Outcome.APPLIED;
        }

        PaymentStatus current = payments.statusOf(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));
        if (current == target) return Outcome.ALREADY_IN_STATE;
        throw new IllegalPaymentTransitionException(paymentId, current, target);
    }

    /**
     * PLANNED -> AWAITING_AUTHORISATION once the provider has created the payment. Recording the
     * same provider payment again (a retried or double-tapped Pay) is a no-op.
     */
    @Transactional
    public Outcome startAuthorisation(long paymentId, String provider, String providerPaymentId, Instant authExpiresAt) {
        if (payments.compareAndSetAwaitingAuthorisation(paymentId, provider, providerPaymentId, authExpiresAt)) {
            return Outcome.APPLIED;
        }
        PayablePayment current = payments.findPayable(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));
        if (current.status() == PaymentStatus.AWAITING_AUTHORISATION
                && provider.equals(current.provider()) && providerPaymentId.equals(current.providerPaymentId())) {
            return Outcome.ALREADY_IN_STATE;
        }
        throw new IllegalPaymentTransitionException(paymentId, current.status(), PaymentStatus.AWAITING_AUTHORISATION);
    }
}

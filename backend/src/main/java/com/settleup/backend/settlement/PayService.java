package com.settleup.backend.settlement;

import com.settleup.backend.account.LinkedAccountRepository;
import com.settleup.backend.settlement.PaymentRepository.PayablePayment;
import com.settleup.backend.settlement.SettlementExceptions.NotYourPaymentException;
import com.settleup.backend.settlement.SettlementExceptions.PayeeNotLinkedException;
import com.settleup.backend.settlement.SettlementExceptions.PaymentNotFoundException;
import com.settleup.backend.settlement.SettlementExceptions.PaymentNotPayableException;
import com.settleup.backend.settlement.provider.PaymentProvider;
import com.settleup.backend.settlement.provider.PaymentProvider.CreatePaymentRequest;
import com.settleup.backend.settlement.provider.PaymentProvider.ProviderPayment;
import com.settleup.backend.settlement.provider.PaymentProviders;
import com.settleup.backend.settlement.provider.ProviderUnavailableException;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;

/**
 * The payer taps Pay. Deliberately not @Transactional: the provider call is a network request,
 * and holding a database transaction (and row locks) open across it would be fragile. Instead
 * the provider call is idempotent on the payment's key, and the status change afterwards is its
 * own short transaction. If we crash in between, tapping Pay again gets the same provider
 * payment back and records it, so no second payment can ever be created.
 */
@Service
public class PayService {

    public record PayResult(long paymentId, PaymentStatus status, String provider, String providerPaymentId,
                            URI authorisationUrl, Instant authExpiresAt) {}

    private final PaymentRepository payments;
    private final PaymentTransitionService transitions;
    private final LinkedAccountRepository linkedAccounts;
    private final PaymentProviders providers;
    private final Clock clock;

    public PayService(PaymentRepository payments, PaymentTransitionService transitions,
                      LinkedAccountRepository linkedAccounts, PaymentProviders providers, Clock clock) {
        this.payments = payments;
        this.transitions = transitions;
        this.linkedAccounts = linkedAccounts;
        this.providers = providers;
        this.clock = clock;
    }

    public PayResult pay(long paymentId, long payerId) {
        PayablePayment p = payments.findPayable(paymentId).orElseThrow(() -> new PaymentNotFoundException(paymentId));
        Instant now = clock.instant();

        if (p.payerId() != payerId) throw new NotYourPaymentException(paymentId, payerId);
        if (!"OPEN".equals(p.runStatus())) throw new PaymentNotPayableException(paymentId, "the settlement run is " + p.runStatus());
        if (!now.isBefore(p.runDeadline())) throw new PaymentNotPayableException(paymentId, "the settlement run deadline has passed");
        if (p.status() != PaymentStatus.PLANNED && p.status() != PaymentStatus.AWAITING_AUTHORISATION) {
            throw new PaymentNotPayableException(paymentId, "it is already " + p.status());
        }

        PaymentProvider provider = p.provider() == null ? providers.active() : providers.byName(p.provider());
        String receivingRef = linkedAccounts.activeReceivingRef(p.payeeId(), provider.name(), now)
                .orElseThrow(() -> new PayeeNotLinkedException(paymentId, p.payeeId()));

        ProviderPayment created;
        try {
            created = provider.createPayment(new CreatePaymentRequest(
                    p.idempotencyKey(), p.amountPence(), receivingRef, "SettleUp " + p.idempotencyKey()));
        } catch (ProviderUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ProviderUnavailableException(provider.name(), e);
        }

        transitions.startAuthorisation(paymentId, provider.name(), created.providerPaymentId(), created.authExpiresAt());
        return new PayResult(paymentId, PaymentStatus.AWAITING_AUTHORISATION, provider.name(),
                created.providerPaymentId(), created.authorisationUrl(), created.authExpiresAt());
    }
}

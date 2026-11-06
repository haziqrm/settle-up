package com.settleup.backend.settlement.provider;

import java.net.URI;
import java.time.Instant;

/**
 * One adapter per payment provider (in-process fake, the Python fake bank, an aggregator,
 * NatWest). The rest of the app only sees this interface.
 */
public interface PaymentProvider {

    record CreatePaymentRequest(String idempotencyKey, long amountPence, String payeeReceivingRef, String reference) {}

    record ProviderPayment(String providerPaymentId, URI authorisationUrl, Instant authExpiresAt) {}

    /** Matches linked_accounts.provider and payments.provider, e.g. "FAKE". */
    String name();

    /**
     * Creates a payment the payer must approve at their bank. Must be idempotent on
     * {@code idempotencyKey}: calling again with the same key returns the same payment,
     * so a retry after a crash or timeout can never create a second one.
     *
     * @throws ProviderUnavailableException if the provider cannot be reached or errors
     */
    ProviderPayment createPayment(CreatePaymentRequest request);
}

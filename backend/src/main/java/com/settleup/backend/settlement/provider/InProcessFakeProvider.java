package com.settleup.backend.settlement.provider;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Stand-in provider until the Python fake bank exists. Keeps payments in memory, keyed by
 * idempotency key like a real provider, and can be switched off to simulate an outage.
 */
@Component
public class InProcessFakeProvider implements PaymentProvider {

    static final Duration AUTH_WINDOW = Duration.ofMinutes(15);

    private final Map<String, ProviderPayment> byIdempotencyKey = new ConcurrentHashMap<>();
    private final AtomicBoolean available = new AtomicBoolean(true);
    private final Clock clock;

    public InProcessFakeProvider(Clock clock) {
        this.clock = clock;
    }

    @Override
    public String name() {
        return "FAKE";
    }

    @Override
    public ProviderPayment createPayment(CreatePaymentRequest request) {
        if (!available.get()) {
            throw new ProviderUnavailableException(name(), new IllegalStateException("fake outage"));
        }
        return byIdempotencyKey.computeIfAbsent(request.idempotencyKey(), key -> {
            String id = "fake-" + UUID.randomUUID();
            return new ProviderPayment(id, URI.create("https://fake-bank.invalid/authorise/" + id),
                    clock.instant().plus(AUTH_WINDOW));
        });
    }

    /** For tests: simulate the provider being down. */
    public void setAvailable(boolean up) {
        available.set(up);
    }

    /** For tests: how many distinct payments exist for this key (0 or 1). */
    public int paymentsFor(String idempotencyKey) {
        return byIdempotencyKey.containsKey(idempotencyKey) ? 1 : 0;
    }
}

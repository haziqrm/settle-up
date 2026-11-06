package com.settleup.backend.settlement.provider;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Picks the provider for new payments (settleup.payments.provider) and finds one by name. */
@Component
public class PaymentProviders {

    private final Map<String, PaymentProvider> byName;
    private final String activeName;

    public PaymentProviders(List<PaymentProvider> providers,
                            @Value("${settleup.payments.provider:FAKE}") String activeName) {
        this.byName = providers.stream().collect(Collectors.toMap(PaymentProvider::name, Function.identity()));
        if (!byName.containsKey(activeName)) {
            throw new IllegalStateException("unknown payment provider " + activeName + ", have " + byName.keySet());
        }
        this.activeName = activeName;
    }

    public PaymentProvider active() {
        return byName.get(activeName);
    }

    /** A payment already started stays with its provider, even if the active one changes. */
    public PaymentProvider byName(String name) {
        PaymentProvider p = byName.get(name);
        if (p == null) throw new IllegalStateException("no payment provider named " + name);
        return p;
    }
}

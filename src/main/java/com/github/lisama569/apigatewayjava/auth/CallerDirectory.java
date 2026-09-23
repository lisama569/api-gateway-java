package com.github.lisama569.apigatewayjava.auth;

import com.github.lisama569.apigatewayjava.model.Caller;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * In-memory directory of known callers. Stand-in for a credential store;
 * deliberately local so tests need no external service.
 */
@Component
public class CallerDirectory {

    private final Map<String, Caller> callers = Map.of(
            "key-premium", new Caller("key-premium", "premium-caller",
                    Set.of("orders", "payments"), 20),
            "key-basic", new Caller("key-basic", "basic-caller",
                    Set.of("orders"), 3),
            "key-zero", new Caller("key-zero", "zero-quota-caller",
                    Set.of("orders"), 0)
    );

    /**
     * @return the caller or {@code null} when the key is missing/unknown.
     */
    public Caller find(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }
        return callers.get(apiKey);
    }
}

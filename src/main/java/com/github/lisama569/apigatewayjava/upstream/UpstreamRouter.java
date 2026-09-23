package com.github.lisama569.apigatewayjava.upstream;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Routes a handler key to an upstream client. Both Spring bean names and the clients'
 * declared {@link UpstreamClient#key()} values are accepted.
 */
@Component
public class UpstreamRouter {

    private final Map<String, UpstreamClient> clients;

    public UpstreamRouter(Map<String, UpstreamClient> injected) {
        Map<String, UpstreamClient> table = new HashMap<>(injected);
        for (UpstreamClient client : injected.values()) {
            table.putIfAbsent(client.key(), client);
        }
        this.clients = Map.copyOf(table);
    }

    public UpstreamClient route(String handler) {
        return clients.get(handler);
    }
}

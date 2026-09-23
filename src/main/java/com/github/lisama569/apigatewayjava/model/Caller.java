package com.github.lisama569.apigatewayjava.model;

import java.util.Set;

/**
 * An authenticated caller principal.
 *
 * @param apiKey    key presented by the caller
 * @param name      display name
 * @param resources resources this caller is allowed to access
 * @param quota     number of permitted calls per fixed quota window
 */
public record Caller(
        String apiKey,
        String name,
        Set<String> resources,
        long quota
) {
}

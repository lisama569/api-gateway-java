package com.github.lisama569.apigatewayjava.identity;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存调用方目录：维护身份 -> 可访问资源集合 + 每窗口配额。
 * 本地实现，测试可通过 register/clear 自定义。
 */
@Component
public class InMemoryCallerDirectory implements CallerDirectory {

    /** "*" 表示该调用方可访问全部资源。 */
    public static final String ALL_RESOURCES = "*";

    private final Map<String, CallerProfile> profiles = new ConcurrentHashMap<>();

    public InMemoryCallerDirectory() {
        register("caller-a", Set.of(ALL_RESOURCES), 20);
        register("caller-b", Set.of("orders", "users"), 5);
        register("caller-c", Set.of("payments"), 3);
    }

    public final void register(String callerId, Set<String> allowedResources, long quotaLimit) {
        profiles.put(callerId, new CallerProfile(callerId, Set.copyOf(allowedResources), quotaLimit));
    }

    public void clear() {
        profiles.clear();
    }

    @Override
    public Optional<CallerProfile> find(String callerId) {
        if (callerId == null || callerId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(profiles.get(callerId));
    }
}

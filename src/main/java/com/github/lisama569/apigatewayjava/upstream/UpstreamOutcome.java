package com.github.lisama569.apigatewayjava.upstream;

import java.util.List;

/**
 * 上游调用归一化结果。单条结果用 {@code response}，批量结果用 {@code items}（逐条保留）。
 * 记录 attemptCount（真实触达上游的次数）用于验证无重复业务副作用；
 * degraded=true 表示因熔断打开或恢复期名额已满而快速降级，未触达上游（attemptCount=0）。
 */
public record UpstreamOutcome(boolean success, UpstreamResponse response,
                              List<Item> items, UpstreamFailureKind failureKind,
                              int attemptCount, boolean degraded) {

    /** 批量中单条子请求的归一化结果，逐条可归因。 */
    public record Item(boolean success, UpstreamResponse response,
                       UpstreamFailureKind failureKind, int attemptCount, boolean degraded) {

        public static Item ok(UpstreamResponse response, int attemptCount) {
            return new Item(true, response, null, attemptCount, false);
        }

        public static Item failed(UpstreamFailureKind kind, int attemptCount, boolean degraded) {
            String label = degraded
                    ? (kind == UpstreamFailureKind.RECOVERY_LIMITED
                        ? "RECOVERY_LIMITED" : "UPSTREAM_UNAVAILABLE")
                    : (kind == null ? "UPSTREAM_ERROR" : kind.name());
            return new Item(false,
                    new UpstreamResponse(degraded ? 503 : 500, "{\"error\":\"" + label + "\"}"),
                    kind, attemptCount, degraded);
        }
    }

    public static UpstreamOutcome ok(UpstreamResponse response, int attemptCount) {
        return new UpstreamOutcome(true, response, null, null, attemptCount, false);
    }

    /**
     * 批量结果：整体成功与否由逐条结果推导。
     * 全成功 / 部分成功 / 全失败三种情况均可从 items 明确区分，
     * 全失败时不会被伪装成部分成功。
     */
    public static UpstreamOutcome batch(List<Item> items) {
        int total = items.size();
        long succeeded = items.stream().filter(Item::success).count();
        int attempts = items.stream().mapToInt(Item::attemptCount).sum();
        boolean anyDegraded = items.stream().anyMatch(Item::degraded);
        List<Item> copy = List.copyOf(items);
        if (succeeded == total) {
            return new UpstreamOutcome(true, null, copy, null, attempts, false);
        }
        UpstreamFailureKind kind = succeeded > 0
                ? UpstreamFailureKind.PARTIAL_FAILURE
                : (anyDegraded ? UpstreamFailureKind.BREAKER_OPEN : UpstreamFailureKind.ERROR_5XX);
        return new UpstreamOutcome(false, null, copy, kind, attempts, anyDegraded);
    }

    /** degraded=true 表示熔断打开/恢复期名额已满导致的快速失败，请求未触达上游（attemptCount=0）。 */
    public static UpstreamOutcome failed(UpstreamFailureKind kind, int attemptCount, boolean degraded) {
        return new UpstreamOutcome(false, null, null, kind, attemptCount, degraded);
    }
}

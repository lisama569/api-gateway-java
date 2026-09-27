package com.github.lisama569.apigatewayjava.upstream;

import java.util.List;

/**
 * 上游调用归一化结果。成功 / 部分失败 / 失败（含两类熔断降级）三类，
 * 并记录 attemptCount（真实触达上游的次数）用于验证无重复业务副作用。
 *
 * 两类降级（{@code degraded=true}，attemptCount=0，均不触达上游）：
 * <ul>
 *   <li>{@link UpstreamFailureKind#UPSTREAM_UNAVAILABLE}：熔断 OPEN，上游仍在隔离期；</li>
 *   <li>{@link UpstreamFailureKind#RECOVERY_LIMITED}：HALF_OPEN 恢复期探测名额用尽。</li>
 * </ul>
 *
 * 批量结果携带 {@link BatchItem} 列表，与子请求位置逐条对齐，
 * 包括整批快速降级的场景，每条失败项都有独立错误归因。
 */
public record UpstreamOutcome(boolean success, UpstreamResponse response,
                              List<UpstreamResponse> responses,
                              List<BatchItem> items,
                              UpstreamFailureKind failureKind,
                              int attemptCount, boolean degraded) {

    public static UpstreamOutcome ok(UpstreamResponse response, int attemptCount) {
        return new UpstreamOutcome(true, response, null, null, null, attemptCount, false);
    }

    public static UpstreamOutcome partial(List<BatchItem> items, int succeeded, int failed) {
        return new UpstreamOutcome(false, null, BatchItem.toResponses(items),
                List.copyOf(items), UpstreamFailureKind.PARTIAL_FAILURE,
                succeeded + failed, false);
    }

    /** 批量调用全部成功：保留每条子响应。 */
    public static UpstreamOutcome batchOk(List<BatchItem> items) {
        return new UpstreamOutcome(true, null, BatchItem.toResponses(items),
                List.copyOf(items), null, items.size(), false);
    }

    /** 批量调用全部失败：逐条失败占位保留，但与部分失败严格区分（BATCH_FAILURE）。 */
    public static UpstreamOutcome batchFailed(List<BatchItem> items) {
        return new UpstreamOutcome(false, null, BatchItem.toResponses(items),
                List.copyOf(items), UpstreamFailureKind.BATCH_FAILURE,
                items.size(), false);
    }

    /**
     * degraded=true 表示熔断器路径导致的快速失败，请求未触达上游（attemptCount=0）。
     *
     * @param kind {@link UpstreamFailureKind#UPSTREAM_UNAVAILABLE}（OPEN 挡回）
     *             或 {@link UpstreamFailureKind#RECOVERY_LIMITED}（恢复期名额用尽）
     */
    public static UpstreamOutcome failed(UpstreamFailureKind kind, int attemptCount, boolean degraded) {
        return new UpstreamOutcome(false, null, null, null, kind, attemptCount, degraded);
    }

    /**
     * 批量整体降级（所有子请求均被熔断器快速失败，未触达上游）。
     * 每条子请求仍逐条保留并独立归因，绝不在整批降级时丢弃明细。
     *
     * @param kind 由逐条结果聚合得出：全部为恢复期名额用尽时为
     *             {@link UpstreamFailureKind#RECOVERY_LIMITED}，否则（含 OPEN）
     *             为 {@link UpstreamFailureKind#UPSTREAM_UNAVAILABLE}
     */
    public static UpstreamOutcome batchDegraded(List<BatchItem> items, UpstreamFailureKind kind) {
        return new UpstreamOutcome(false, null, BatchItem.toResponses(items),
                List.copyOf(items), kind, 0, true);
    }
}

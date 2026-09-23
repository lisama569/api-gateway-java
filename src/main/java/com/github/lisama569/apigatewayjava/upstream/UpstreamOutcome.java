package com.github.lisama569.apigatewayjava.upstream;

import java.util.List;

/**
 * 上游调用归一化结果。成功 / 部分失败 / 失败（含熔断降级）三类，
 * 并记录 attemptCount（真实触达上游的次数）用于验证无重复业务副作用。
 */
public record UpstreamOutcome(boolean success, UpstreamResponse response,
                              List<UpstreamResponse> responses, UpstreamFailureKind failureKind,
                              int attemptCount, boolean degraded) {

    public static UpstreamOutcome ok(UpstreamResponse response, int attemptCount) {
        return new UpstreamOutcome(true, response, null, null, attemptCount, false);
    }

    public static UpstreamOutcome partial(List<UpstreamResponse> responses, int succeeded, int failed) {
        return new UpstreamOutcome(false, null, List.copyOf(responses),
                UpstreamFailureKind.PARTIAL_FAILURE, succeeded + failed, false);
    }

    /** 批量调用全部成功：保留每条子响应。 */
    public static UpstreamOutcome batchOk(List<UpstreamResponse> responses, int count) {
        return new UpstreamOutcome(true, null, List.copyOf(responses), null, count, false);
    }

    /** degraded=true 表示熔断器打开导致的快速失败，请求未触达上游（attemptCount=0）。 */
    public static UpstreamOutcome failed(UpstreamFailureKind kind, int attemptCount, boolean degraded) {
        return new UpstreamOutcome(false, null, null, kind, attemptCount, degraded);
    }
}

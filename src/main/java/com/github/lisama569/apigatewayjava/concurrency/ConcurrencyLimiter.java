package com.github.lisama569.apigatewayjava.concurrency;

/**
 * 在途并发名额分配器（按调用方，或调用方+资源维度）。
 *
 * 超出上限的请求必须快速失败（{@link #tryAcquire} 返回 false），而不是排队等待；
 * 请求处理结束后必须调用 {@link #release} 立即归还名额，压力回落后自动恢复放行。
 */
public interface ConcurrencyLimiter {

    /** 尝试占用一个在途名额：无空闲名额时立即返回 false，不阻塞、不排队。 */
    boolean tryAcquire(String callerId, String resource);

    /** 归还一个在途名额，必须且仅调用一次（建议放在 finally 中）。 */
    void release(String callerId, String resource);

    /** 当前在途数（观测/日志用）。 */
    int inFlight(String callerId, String resource);

    /** 清空全部名额状态（测试隔离用）。 */
    void reset();
}

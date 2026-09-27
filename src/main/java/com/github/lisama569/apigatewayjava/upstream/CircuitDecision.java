package com.github.lisama569.apigatewayjava.upstream;

/**
 * 熔断器放行判定结果。两种「快速失败」必须可区分：
 * <ul>
 *   <li>{@link #REJECTED_OPEN}：熔断器打开，上游仍在故障隔离期，请求被直接挡住；</li>
 *   <li>{@link #REJECTED_HALF_OPEN}：上游处于恢复探测期，探测名额已用完，
 *       请求被快速失败——调用方可据此判断上游已在恢复、可适当提前重试。</li>
 * </ul>
 */
public enum CircuitDecision {
    /** 放行，请求将真正触达上游。 */
    ALLOWED,
    /** 熔断器 OPEN：隔离期快速失败，不触达上游。 */
    REJECTED_OPEN,
    /** 熔断器 HALF_OPEN：恢复期探测名额已用完，快速失败，不触达上游。 */
    REJECTED_HALF_OPEN
}

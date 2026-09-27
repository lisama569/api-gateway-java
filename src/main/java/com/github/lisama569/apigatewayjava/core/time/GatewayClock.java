package com.github.lisama569.apigatewayjava.core.time;

import java.util.function.LongSupplier;

/**
 * 网关统一时钟。
 *
 * <ul>
 *   <li>生产环境：{@link #system()} 直接读取系统毫秒时间；</li>
 *   <li>测试环境：可注入 {@code () -> controlledMillis} 形式的可控时钟，
 *       用「推进虚拟时间」代替 Thread.sleep，杜绝基于固定等待的偶发断言。</li>
 * </ul>
 */
@FunctionalInterface
public interface GatewayClock extends LongSupplier {

    long nowMillis();

    @Override
    default long getAsLong() {
        return nowMillis();
    }

    static GatewayClock system() {
        return System::currentTimeMillis;
    }
}

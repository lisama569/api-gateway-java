package com.github.lisama569.apigatewayjava.core.time;

import org.springframework.stereotype.Component;

/** 生产环境默认时钟：读取系统毫秒时间。测试可用自己的 {@link GatewayClock} Bean 覆盖。 */
@Component
public class SystemGatewayClock implements GatewayClock {

    @Override
    public long nowMillis() {
        return System.currentTimeMillis();
    }
}

package com.elibrary.shared.kernel;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/** 以 UTC 系统时钟实作正式执行环境的时间来源。 */
@Component
public class SystemClockProvider implements ClockProvider {
    private final Clock clock;

    public SystemClockProvider() {
        this(Clock.systemUTC());
    }

    /** 套件内建构子供测试注入可控制的 {@link Clock}。 */
    SystemClockProvider(Clock clock) {
        this.clock = clock;
    }

    /** {@inheritDoc} */
    @Override
    public Instant now() {
        return clock.instant();
    }
}

package com.elibrary.shared.kernel;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

@Component
public class SystemClockProvider implements ClockProvider {
    private final Clock clock;

    public SystemClockProvider() {
        this(Clock.systemUTC());
    }

    SystemClockProvider(Clock clock) {
        this.clock = clock;
    }

    @Override
    public Instant now() {
        return clock.instant();
    }
}

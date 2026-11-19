package com.elibrary.shared.kernel;

import java.time.Instant;

public interface ClockProvider {
    Instant now();
}

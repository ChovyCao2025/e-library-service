package com.elibrary.shared.web;

import com.elibrary.shared.kernel.AppProperties;
import com.elibrary.shared.kernel.ClockProvider;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CursorCodecTest {
    @Test
    void signedCursorRoundTripsAndRejectsQueryChanges() {
        var clock = new MutableClock(Instant.parse("2026-11-19T00:00:00Z"));
        var codec = codec(clock);
        String encoded = codec.encode("query-a", "DB", "42", 20);

        var state = codec.decode(encoded, "query-a", 20);
        assertThat(state.after()).isEqualTo("42");
        assertThat(state.backend()).isEqualTo("DB");
        assertThatThrownBy(() -> codec.decode(encoded, "query-b", 20))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.code()).isEqualTo(ErrorCode.CURSOR_INVALID));
    }

    @Test
    void expiredCursorHasDedicatedError() {
        var clock = new MutableClock(Instant.parse("2026-11-19T00:00:00Z"));
        var codec = codec(clock);
        String encoded = codec.encode("query-a", "DB", "42", 20);
        clock.now = clock.now.plusSeconds(61);

        assertThatThrownBy(() -> codec.decode(encoded, "query-a", 20))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.code()).isEqualTo(ErrorCode.CURSOR_EXPIRED));
    }

    private static CursorCodec codec(ClockProvider clock) {
        var cursor = new AppProperties.Cursor("01234567890123456789012345678901", Duration.ofSeconds(60));
        var properties = new AppProperties("all", null, null, null, cursor, 0);
        return new CursorCodec(new ObjectMapper(), clock, properties);
    }

    private static final class MutableClock implements ClockProvider {
        private Instant now;
        private MutableClock(Instant now) { this.now = now; }
        @Override public Instant now() { return now; }
    }
}

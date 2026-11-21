package com.elibrary.shared.web.api;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** E-Library 对外及闸道内部使用的 HTTP 标头名称。 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ApiHeaders {
    public static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    public static final String IDEMPOTENT_REPLAY = "Idempotent-Replay";
    public static final String USER_ID = "X-User-Id";
    public static final String USER_ROLE = "X-Role";
    public static final String TRACE_PARENT = "traceparent";
    public static final String TRACE_ID = "X-Trace-Id";
}

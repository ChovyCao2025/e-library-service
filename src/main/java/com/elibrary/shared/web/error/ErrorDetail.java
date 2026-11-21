package com.elibrary.shared.web.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;

import java.util.Optional;
import java.util.UUID;

/**
 * 统一失败回应的 data 内容。
 * 呼叫端使用 traceId 与服务端日志关联，并依 retryable 决定是否可安全重试。
 */
@Getter
@RequiredArgsConstructor
public final class ErrorDetail {
    /** 本次 HTTP 请求的追踪识别码。 */
    private final String traceId;

    /** 此错误是否适合由呼叫端依退避策略重试。 */
    private final boolean retryable;

    /** 已建立的借阅请求识别码；错误与特定请求无关时为 {@code null}。 */
    private final String requestId;

    /** 从目前请求上下文建立错误资料，缺少追踪识别码时产生新的 UUID。 */
    public static ErrorDetail from(boolean retryable, Long requestId) {
        String traceId = Optional.ofNullable(MDC.get(DiagnosticContext.TRACE_ID))
                .filter(value -> !value.isBlank())
                .orElseGet(() -> UUID.randomUUID().toString());
        return new ErrorDetail(traceId, retryable,
                requestId == null ? null : Long.toUnsignedString(requestId));
    }
}

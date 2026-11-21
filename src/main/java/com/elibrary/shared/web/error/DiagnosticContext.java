package com.elibrary.shared.web.error;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 应用程式写入 SLF4J MDC 的诊断栏位名称。 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class DiagnosticContext {
    public static final String TRACE_ID = "traceId";
}

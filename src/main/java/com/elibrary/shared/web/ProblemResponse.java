package com.elibrary.shared.web;

public record ProblemResponse(
        String type,
        String title,
        int status,
        String code,
        String detail,
        String traceId,
        boolean retryable,
        String requestId
) {
}

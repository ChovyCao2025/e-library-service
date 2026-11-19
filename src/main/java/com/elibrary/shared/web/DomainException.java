package com.elibrary.shared.web;

import org.springframework.http.HttpStatus;

public class DomainException extends RuntimeException {
    private final ErrorCode code;
    private final HttpStatus status;
    private final boolean retryable;
    private final Long requestId;

    public DomainException(ErrorCode code, HttpStatus status, String detail, boolean retryable) {
        this(code, status, detail, retryable, null);
    }

    public DomainException(ErrorCode code, HttpStatus status, String detail, boolean retryable, Long requestId) {
        super(detail);
        this.code = code;
        this.status = status;
        this.retryable = retryable;
        this.requestId = requestId;
    }

    public ErrorCode code() { return code; }
    public HttpStatus status() { return status; }
    public boolean retryable() { return retryable; }
    public Long requestId() { return requestId; }

    public static DomainException notFound(ErrorCode code, String detail) {
        return new DomainException(code, HttpStatus.NOT_FOUND, detail, false);
    }

    public static DomainException conflict(ErrorCode code, String detail) {
        return new DomainException(code, HttpStatus.CONFLICT, detail, false);
    }
}

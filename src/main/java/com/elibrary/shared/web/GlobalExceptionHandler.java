package com.elibrary.shared.web;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import jakarta.validation.ConstraintViolationException;

import java.util.Optional;
import java.util.UUID;

/** 將領域錯誤與框架例外統一轉換為 RFC 9457 Problem Details 回應。 */
@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(DomainException.class)
    ResponseEntity<ProblemResponse> handleDomain(DomainException exception, HttpServletRequest request) {
        var headers = problemHeaders();
        if (exception.code() == ErrorCode.REQUEST_IN_PROGRESS) {
            headers.set(HttpHeaders.RETRY_AFTER, "1");
        }
        return new ResponseEntity<>(problem(exception.code(), exception.status(), exception.getMessage(),
                exception.retryable(), exception.requestId()), headers, exception.status());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class,
            HttpMessageNotReadableException.class, IllegalArgumentException.class,
            ConstraintViolationException.class})
    ResponseEntity<ProblemResponse> handleValidation(Exception exception) {
        return new ResponseEntity<>(problem(ErrorCode.VALIDATION_FAILED, HttpStatus.BAD_REQUEST,
                "Request validation failed", false, null), problemHeaders(), HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<ProblemResponse> handleMissingHeader(MissingRequestHeaderException exception) {
        var code = "Idempotency-Key".equalsIgnoreCase(exception.getHeaderName())
                ? ErrorCode.MISSING_IDEMPOTENCY_KEY : ErrorCode.VALIDATION_FAILED;
        return new ResponseEntity<>(problem(code, HttpStatus.BAD_REQUEST, exception.getMessage(), false, null),
                problemHeaders(), HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ProblemResponse> handleIntegrity(DataIntegrityViolationException exception) {
        log.warn("Database constraint rejected request", exception);
        return new ResponseEntity<>(problem(ErrorCode.VERSION_CONFLICT, HttpStatus.CONFLICT,
                "The requested change conflicts with current state", false, null), problemHeaders(), HttpStatus.CONFLICT);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemResponse> handleUnexpected(Exception exception) {
        log.error("Unhandled request error", exception);
        return new ResponseEntity<>(problem(ErrorCode.INTERNAL_ERROR, HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred", false, null), problemHeaders(), HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private static ProblemResponse problem(ErrorCode code, HttpStatus status, String detail,
                                           boolean retryable, Long requestId) {
        String traceId = Optional.ofNullable(MDC.get("traceId")).orElseGet(() -> UUID.randomUUID().toString());
        return new ProblemResponse("urn:elibrary:error:" + code.name().toLowerCase(), code.name(), status.value(),
                code.name(), detail, traceId, retryable,
                requestId == null ? null : Long.toUnsignedString(requestId));
    }

    private static HttpHeaders problemHeaders() {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        return headers;
    }
}

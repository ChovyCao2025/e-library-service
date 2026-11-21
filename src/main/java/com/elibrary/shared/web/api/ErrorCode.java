package com.elibrary.shared.web.api;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 对外 API 的稳定结果码定义。
 * 每个项目集中维护业务码与预设讯息，避免各处自行拼装回应语义。
 */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {
    SUCCESS("0000", "success"),

    VALIDATION_FAILED("VALIDATION_FAILED", "Request validation failed"),
    MISSING_IDEMPOTENCY_KEY("MISSING_IDEMPOTENCY_KEY", "Idempotency-Key is required"),

    UNAUTHENTICATED("UNAUTHENTICATED", "Authentication is required"),
    FORBIDDEN("FORBIDDEN", "Administrator role is required"),

    BOOK_NOT_FOUND("BOOK_NOT_FOUND", "Book was not found"),
    LOAN_NOT_FOUND("LOAN_NOT_FOUND", "Loan was not found"),

    BOOK_SOLD_OUT("BOOK_SOLD_OUT", "No lending seat is available"),
    BOOK_NOT_BORROWABLE("BOOK_NOT_BORROWABLE", "Book is not published"),
    ALREADY_BORROWED("ALREADY_BORROWED", "User already has an active loan for this book"),
    LOAN_LIMIT_EXCEEDED("LOAN_LIMIT_EXCEEDED", "Active loan limit reached"),
    DUPLICATE_ISBN("DUPLICATE_ISBN", "ISBN already exists"),
    STOCK_BELOW_BORROWED("STOCK_BELOW_BORROWED",
            "New capacity is below the number of active loans"),
    VERSION_CONFLICT("VERSION_CONFLICT",
            "The requested change conflicts with current state"),
    IDEMPOTENCY_KEY_REUSED("IDEMPOTENCY_KEY_REUSED",
            "Idempotency-Key was already used for another book"),

    RATE_LIMITED("RATE_LIMITED", "Too many requests"),
    SEARCH_UNAVAILABLE("SEARCH_UNAVAILABLE", "Catalog search is temporarily unavailable"),
    INTERNAL_ERROR("INTERNAL_ERROR", "An unexpected error occurred");

    /** 对外传输且不可随意变更的业务码。 */
    private final String code;

    /** 未提供更具体业务说明时使用的安全预设讯息。 */
    private final String msg;
}

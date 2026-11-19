package com.elibrary.lending.interfaces;

import com.elibrary.lending.infrastructure.BorrowRequestRow;
import com.elibrary.lending.infrastructure.LoanRow;
import jakarta.validation.constraints.NotBlank;

import java.time.ZoneOffset;
import java.util.List;

public final class LendingDtos {
    private LendingDtos() { }

    public record BorrowRequest(@NotBlank String bookId) { }

    public record LoanResponse(
            String requestId, String loanId, String bookId, String status,
            java.time.Instant borrowedAt, java.time.Instant dueAt,
            java.time.Instant returnedAt, String returnReason
    ) {
        public static LoanResponse from(LoanRow row) {
            return new LoanResponse(Long.toUnsignedString(row.requestId()), Long.toUnsignedString(row.loanId()),
                    Long.toUnsignedString(row.bookId()), row.status(), row.borrowedAt().toInstant(ZoneOffset.UTC),
                    row.dueAt().toInstant(ZoneOffset.UTC),
                    row.returnedAt() == null ? null : row.returnedAt().toInstant(ZoneOffset.UTC), row.returnReason());
        }
    }

    public record BorrowOutcome(LoanResponse loan, boolean replay) { }

    public record RequestStatusResponse(String requestId, String status, boolean retryable,
                                        String resultCode, String loanId) {
        public static RequestStatusResponse from(BorrowRequestRow row) {
            return new RequestStatusResponse(Long.toUnsignedString(row.requestId()), row.status(),
                    "RETRYABLE".equals(row.status()), row.resultCode(),
                    "SUCCEEDED".equals(row.status()) ? Long.toUnsignedString(row.loanId()) : null);
        }
    }

    public record LoanPage(List<LoanResponse> items, String nextCursor, boolean hasMore) { }
}

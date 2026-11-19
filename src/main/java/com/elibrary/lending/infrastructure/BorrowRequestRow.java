package com.elibrary.lending.infrastructure;

import java.time.LocalDateTime;

public record BorrowRequestRow(
        long requestId, long userId, String idemKey, long bookId, String requestHash, long loanId,
        String status, int attempt, String attemptToken, String admissionMode,
        Long quotaGeneration, Long stockGeneration, LocalDateTime leaseUntil,
        boolean quotaSettled, boolean stockSettled, String resultCode,
        LocalDateTime createdAt, LocalDateTime updatedAt
) {
}

package com.elibrary.lending.infrastructure;

import java.time.LocalDateTime;

public record LoanRow(
        long loanId, long requestId, long userId, long bookId, String idemKey, String status, Integer activeKey,
        LocalDateTime borrowedAt, LocalDateTime dueAt, LocalDateTime returnedAt, String returnReason,
        String policyVersion, long version, LocalDateTime createdAt, LocalDateTime updatedAt
) {
}

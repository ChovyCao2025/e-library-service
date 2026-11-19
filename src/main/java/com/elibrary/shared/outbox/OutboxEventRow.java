package com.elibrary.shared.outbox;

import java.time.LocalDateTime;

public record OutboxEventRow(
        long id,
        String aggregateType,
        long aggregateId,
        String eventType,
        long aggregateVersion,
        String payload,
        String status,
        String ownerToken,
        LocalDateTime leaseUntil,
        LocalDateTime nextAttemptAt,
        int attempts,
        LocalDateTime createdAt,
        LocalDateTime sentAt,
        String lastError
) {
}

package com.elibrary.lending.infrastructure;

import java.time.LocalDateTime;

public record ProjectionControlRow(
        String resourceType, long resourceId, long generation, String state,
        String ownerToken, LocalDateTime leaseUntil, LocalDateTime updatedAt
) {
}

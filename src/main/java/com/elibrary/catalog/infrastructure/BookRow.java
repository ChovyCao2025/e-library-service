package com.elibrary.catalog.infrastructure;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record BookRow(
        long bookId,
        String isbn,
        String title,
        String subtitle,
        String authors,
        String publisher,
        long categoryId,
        String language,
        String description,
        String coverUrl,
        LocalDate publishedDate,
        String format,
        String status,
        long version,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        Integer totalCopies,
        Integer availableCopies,
        Long stockVersion
) {
}

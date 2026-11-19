package com.elibrary.catalog.interfaces;

import com.elibrary.catalog.infrastructure.BookRow;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

public final class BookDtos {
    private BookDtos() {
    }

    public record Availability(Integer available, Integer total, Instant asOf, String source) {
    }

    public record BookResponse(
            String bookId, String isbn, String title, String subtitle, List<String> authors,
            String publisher, String categoryId, String language, String description, String coverUrl,
            LocalDate publishedDate, String format, String status, long version, Availability availability
    ) {
        public static BookResponse from(BookRow row, ObjectMapper objectMapper, Instant asOf) {
            try {
                List<String> authors = objectMapper.readValue(row.authors(), new TypeReference<>() {});
                var availability = row.availableCopies() == null ? null
                        : new Availability(row.availableCopies(), row.totalCopies(), asOf, "DATABASE");
                return new BookResponse(Long.toUnsignedString(row.bookId()), row.isbn(), row.title(), row.subtitle(),
                        authors, row.publisher(), Long.toUnsignedString(row.categoryId()), row.language(),
                        row.description(), row.coverUrl(), row.publishedDate(), row.format(), row.status(),
                        row.version(), availability);
            } catch (JacksonException exception) {
                throw new IllegalStateException("Invalid authors JSON in catalog", exception);
            }
        }
    }

    public record BookPage(List<BookResponse> items, String nextCursor, boolean hasMore) {
    }

    public record BookUpsertRequest(
            @Size(max = 20) String isbn,
            @NotBlank @Size(max = 200) String title,
            @Size(max = 200) String subtitle,
            @NotEmpty List<@NotBlank @Size(max = 100) String> authors,
            @Size(max = 100) String publisher,
            @NotBlank String categoryId,
            @NotBlank @Size(max = 10) String language,
            @Size(max = 10000) String description,
            @Size(max = 500) String coverUrl,
            LocalDate publishedDate,
            @NotBlank String format,
            Long version
    ) {
    }

    public record StockRequest(@Min(0) @Max(100000) int totalCopies) {
    }
}

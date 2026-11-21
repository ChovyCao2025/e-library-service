package com.elibrary.catalog.domain;

import lombok.Getter;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * 书籍可编辑中继资料的领域值物件。
 * 本物件只表达书目语意，不包含 HTTP DTO、JSON 字串或 MyBatis 等基础设施细节。
 */
@Getter
public final class BookMetadata {
    private final String isbn;
    private final String title;
    private final String subtitle;
    private final List<String> authors;
    private final String publisher;
    private final long categoryId;
    private final String language;
    private final String description;
    private final String coverUrl;
    private final LocalDate publishedDate;
    private final BookFormat format;

    public BookMetadata(String isbn, String title, String subtitle, List<String> authors,
                        String publisher, long categoryId, String language, String description,
                        String coverUrl, LocalDate publishedDate, BookFormat format) {
        this.isbn = normalizeOptional(isbn);
        this.title = requireText(title, "title");
        this.subtitle = subtitle;
        this.authors = validatedAuthors(authors);
        this.publisher = publisher;
        if (categoryId <= 0) {
            throw new IllegalArgumentException("categoryId must be positive");
        }
        this.categoryId = categoryId;
        this.language = requireText(language, "language");
        this.description = description;
        this.coverUrl = coverUrl;
        this.publishedDate = publishedDate;
        this.format = Objects.requireNonNull(format, "format must not be null");
    }

    private static List<String> validatedAuthors(List<String> authors) {
        if (authors == null || authors.isEmpty() || authors.stream().anyMatch(BookMetadata::isBlank)) {
            throw new IllegalArgumentException("authors must contain non-blank values");
        }
        return List.copyOf(authors);
    }

    private static String requireText(String value, String field) {
        if (isBlank(value)) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static String normalizeOptional(String value) {
        return isBlank(value) ? null : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) return true;
        if (!(object instanceof BookMetadata that)) return false;
        return categoryId == that.categoryId
                && Objects.equals(isbn, that.isbn)
                && title.equals(that.title)
                && Objects.equals(subtitle, that.subtitle)
                && authors.equals(that.authors)
                && Objects.equals(publisher, that.publisher)
                && language.equals(that.language)
                && Objects.equals(description, that.description)
                && Objects.equals(coverUrl, that.coverUrl)
                && Objects.equals(publishedDate, that.publishedDate)
                && format == that.format;
    }

    @Override
    public int hashCode() {
        return Objects.hash(isbn, title, subtitle, authors, publisher, categoryId, language,
                description, coverUrl, publishedDate, format);
    }
}

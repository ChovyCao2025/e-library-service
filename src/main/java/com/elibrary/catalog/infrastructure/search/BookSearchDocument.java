package com.elibrary.catalog.infrastructure.search;

import com.elibrary.catalog.application.BookSearchReader.BookSearchView;
import com.elibrary.catalog.domain.BookFormat;
import com.elibrary.catalog.domain.BookStatus;
import com.elibrary.catalog.infrastructure.BookRow;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;
import org.springframework.data.elasticsearch.annotations.WriteTypeHint;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/** Elasticsearch 中可重建的 Catalog 书目搜索投影。 */
@Document(indexName = "catalog-book-read", createIndex = false, writeTypeHint = WriteTypeHint.FALSE)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class BookSearchDocument {
    @Id
    @Field(type = FieldType.Keyword)
    private String bookId;

    @Field(type = FieldType.Long)
    private long bookIdSort;

    @Field(type = FieldType.Keyword)
    private String isbn;

    @Field(type = FieldType.Text)
    private String title;

    @Field(type = FieldType.Keyword)
    private String titlePrefix;

    @Field(type = FieldType.Text)
    private String subtitle;

    @Field(type = FieldType.Text)
    private List<String> authors;

    @Field(type = FieldType.Text)
    private String publisher;

    @Field(type = FieldType.Long)
    private long categoryId;

    @Field(type = FieldType.Keyword)
    private String language;

    @Field(type = FieldType.Text)
    private String description;

    @Field(type = FieldType.Keyword)
    private String coverUrl;

    @Field(type = FieldType.Keyword)
    private String publishedDate;

    @Field(type = FieldType.Keyword)
    private String format;

    @Field(type = FieldType.Keyword)
    private String status;

    @Field(type = FieldType.Long)
    private long version;

    public static BookSearchDocument from(BookRow row, ObjectMapper objectMapper) {
        try {
            List<String> authors = objectMapper.readValue(row.getAuthors(), new TypeReference<>() { });
            return create(row.getBookId(), row.getIsbn(), row.getTitle(), row.getSubtitle(), authors,
                    row.getPublisher(), row.getCategoryId(), row.getLanguage(), row.getDescription(),
                    row.getCoverUrl(), row.getPublishedDate(), row.getFormat(), row.getStatus(), row.getVersion());
        } catch (JacksonException exception) {
            throw new IllegalStateException("Invalid authors JSON in catalog", exception);
        }
    }

    public static BookSearchDocument from(JsonNode payload, long aggregateVersion, ObjectMapper objectMapper) {
        try {
            List<String> authors = objectMapper.readValue(payload.path("authors").asText(),
                    new TypeReference<>() { });
            return create(Long.parseUnsignedLong(payload.path("bookId").asText()), nullable(payload, "isbn"),
                    payload.path("title").asText(), nullable(payload, "subtitle"), authors,
                    nullable(payload, "publisher"), Long.parseUnsignedLong(payload.path("categoryId").asText()),
                    payload.path("language").asText(), nullable(payload, "description"),
                    nullable(payload, "coverUrl"), localDate(payload, "publishedDate"),
                    BookFormat.valueOf(payload.path("format").asText()),
                    BookStatus.valueOf(payload.path("status").asText()), aggregateVersion);
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid catalog event payload", exception);
        }
    }

    public BookSearchView toView() {
        return new BookSearchView(bookIdSort, isbn, title, subtitle, List.copyOf(authors), publisher,
                categoryId, language, description, coverUrl,
                publishedDate == null ? null : LocalDate.parse(publishedDate),
                BookFormat.valueOf(format), BookStatus.valueOf(status), version);
    }

    private static BookSearchDocument create(long bookId, String isbn, String title, String subtitle,
                                              List<String> authors, String publisher, long categoryId,
                                              String language, String description, String coverUrl,
                                              LocalDate publishedDate, BookFormat format,
                                              BookStatus status, long version) {
        return new BookSearchDocument(Long.toUnsignedString(bookId), bookId, isbn, title,
                title.toLowerCase(Locale.ROOT), subtitle, List.copyOf(authors), publisher,
                categoryId, language, description, coverUrl,
                publishedDate == null ? null : publishedDate.toString(),
                format.name(), status.name(), version);
    }

    private static String nullable(JsonNode payload, String field) {
        JsonNode value = payload.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static LocalDate localDate(JsonNode payload, String field) {
        String value = nullable(payload, field);
        return value == null ? null : LocalDate.parse(value);
    }
}

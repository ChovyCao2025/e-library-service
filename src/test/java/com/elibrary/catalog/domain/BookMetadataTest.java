package com.elibrary.catalog.domain;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证书目中继资料的领域不变量与集合不可变性。 */
class BookMetadataTest {
    @Test
    void normalizesOptionalIsbnAndCopiesAuthors() {
        var authors = new ArrayList<>(List.of("Author One"));
        var metadata = metadata(" ", authors);

        authors.add("Author Two");

        assertThat(metadata.getIsbn()).isNull();
        assertThat(metadata.getAuthors()).containsExactly("Author One");
        assertThatThrownBy(() -> metadata.getAuthors().add("Author Three"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsMissingAuthors() {
        assertThatThrownBy(() -> metadata("9780000000000", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("authors");
    }

    private static BookMetadata metadata(String isbn, List<String> authors) {
        return new BookMetadata(isbn, "Domain-Driven Design", null, authors,
                "Example Publisher", 1L, "zh-TW", null, null, null, BookFormat.EBOOK);
    }
}

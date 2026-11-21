package com.elibrary.catalog.application;

import com.elibrary.catalog.domain.BookFormat;
import com.elibrary.catalog.domain.BookStatus;

import java.time.LocalDate;
import java.util.List;

/** Catalog 搜索读模型连接埠；实现可由 Elasticsearch 等可重建投影提供。 */
public interface BookSearchReader {
    List<BookSearchView> search(BookSearchQuery query, int limit);

    record BookSearchQuery(String prefix, Long categoryId, String language, BookFormat format,
                           long afterBookId) {
    }

    record BookSearchView(long bookId, String isbn, String title, String subtitle,
                          List<String> authors, String publisher, long categoryId,
                          String language, String description, String coverUrl,
                          LocalDate publishedDate, BookFormat format, BookStatus status,
                          long version) {
    }
}

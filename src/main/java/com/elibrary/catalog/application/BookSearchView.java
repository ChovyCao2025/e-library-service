package com.elibrary.catalog.application;

import com.elibrary.catalog.domain.BookFormat;
import com.elibrary.catalog.domain.BookStatus;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;
import java.util.List;

/** Catalog 搜索投影回传给应用服务的不可变书目视图。 */
@Getter
@RequiredArgsConstructor
public final class BookSearchView {
    private final long bookId;
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
    private final BookStatus status;
    private final long version;
}

package com.elibrary.catalog.application;

import com.elibrary.catalog.domain.BookFormat;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** Elasticsearch 书目投影使用的不可变搜索条件。 */
@Getter
@RequiredArgsConstructor
public final class BookSearchQuery {
    private final String prefix;
    private final Long categoryId;
    private final String language;
    private final BookFormat format;
    private final long afterBookId;
}

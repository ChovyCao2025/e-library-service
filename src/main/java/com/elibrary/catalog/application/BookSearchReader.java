package com.elibrary.catalog.application;

import java.util.List;

/** Catalog 搜索读模型连接埠；实现可由 Elasticsearch 等可重建投影提供。 */
public interface BookSearchReader {
    List<BookSearchView> search(BookSearchQuery query, int limit);
}

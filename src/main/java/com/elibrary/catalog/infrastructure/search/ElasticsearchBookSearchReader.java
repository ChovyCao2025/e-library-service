package com.elibrary.catalog.infrastructure.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.PrefixQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.TermQuery;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import com.elibrary.catalog.application.BookSearchReader;
import com.elibrary.catalog.application.BookSearchUnavailableException;
import com.elibrary.catalog.domain.BookStatus;
import com.elibrary.shared.kernel.AppProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 使用 Elasticsearch 前缀投影查询已上架书目。 */
@Component
@ConditionalOnProperty(name = "app.search.enabled", havingValue = "true")
@RequiredArgsConstructor
public class ElasticsearchBookSearchReader implements BookSearchReader {
    private final ElasticsearchClient client;
    private final CatalogSearchIndexManager indexManager;
    private final AppProperties properties;

    @Override
    public List<BookSearchView> search(BookSearchQuery query, int limit) {
        try {
            indexManager.ensureIndex();
            SearchRequest.Builder request = new SearchRequest.Builder()
                    .index(properties.getSearch().getReadAlias())
                    .size(limit)
                    .query(buildQuery(query))
                    .sort(s -> s.field(f -> f.field("bookIdSort").order(SortOrder.Asc)))
                    .timeout("2s");
            if (query.afterBookId() > 0) {
                request.searchAfter(query.afterBookId());
            }
            return client.search(request.build(), BookSearchDocument.class)
                    .hits().hits().stream()
                    .map(hit -> hit.source())
                    .filter(java.util.Objects::nonNull)
                    .map(BookSearchDocument::toView)
                    .toList();
        } catch (IOException | RuntimeException exception) {
            throw new BookSearchUnavailableException("Catalog search is unavailable", exception);
        }
    }

    private static Query buildQuery(BookSearchQuery query) {
        List<Query> filters = new ArrayList<>();
        filters.add(term("status", BookStatus.PUBLISHED.name()));
        if (query.categoryId() != null) {
            filters.add(term("categoryId", query.categoryId()));
        }
        if (query.language() != null && !query.language().isBlank()) {
            filters.add(term("language", query.language()));
        }
        if (query.format() != null) {
            filters.add(term("format", query.format().name()));
        }
        Query prefix = PrefixQuery.of(p -> p.field("titlePrefix")
                .value(query.prefix().toLowerCase(Locale.ROOT)))._toQuery();
        return BoolQuery.of(b -> b.must(prefix).filter(filters))._toQuery();
    }

    private static Query term(String field, String value) {
        return TermQuery.of(t -> t.field(field).value(value))._toQuery();
    }

    private static Query term(String field, long value) {
        return TermQuery.of(t -> t.field(field).value(value))._toQuery();
    }
}

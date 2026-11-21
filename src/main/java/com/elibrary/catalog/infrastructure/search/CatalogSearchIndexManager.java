package com.elibrary.catalog.infrastructure.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.mapping.DynamicMapping;
import co.elastic.clients.elasticsearch.indices.SegmentSortOrder;
import com.elibrary.shared.kernel.AppProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;

/** 建立版本化书目索引及稳定读写别名。 */
@Component
@ConditionalOnProperty(name = "app.search.enabled", havingValue = "true")
@RequiredArgsConstructor
public class CatalogSearchIndexManager {
    private final ElasticsearchClient client;
    private final AppProperties properties;
    private volatile boolean ready;

    public synchronized void ensureIndex() {
        if (ready) {
            return;
        }
        var search = properties.getSearch();
        try {
            boolean exists = client.indices().exists(e -> e.index(search.getPhysicalIndex())).value();
            if (!exists) {
                client.indices().create(c -> c
                        .index(search.getPhysicalIndex())
                        .settings(s -> s
                                .numberOfShards("1")
                                .numberOfReplicas("0")
                                .sort(sort -> sort
                                        .field("bookIdSort")
                                        .order(SegmentSortOrder.Asc)))
                        .mappings(m -> m
                                .dynamic(DynamicMapping.Strict)
                                .properties("bookId", p -> p.keyword(k -> k))
                                .properties("bookIdSort", p -> p.long_(n -> n))
                                .properties("isbn", p -> p.keyword(k -> k.ignoreAbove(64)))
                                .properties("title", p -> p.text(t -> t))
                                .properties("titlePrefix", p -> p.text(t -> t
                                        .analyzer("keyword")
                                        .searchAnalyzer("keyword")
                                        .norms(false)
                                        .indexPrefixes(prefixes -> prefixes
                                                .minChars(1)
                                                .maxChars(19))))
                                .properties("subtitle", p -> p.text(t -> t))
                                .properties("authors", p -> p.text(t -> t))
                                .properties("publisher", p -> p.text(t -> t))
                                .properties("categoryId", p -> p.long_(n -> n))
                                .properties("language", p -> p.keyword(k -> k.ignoreAbove(32)))
                                .properties("description", p -> p.text(t -> t))
                                .properties("coverUrl", p -> p.keyword(k -> k.ignoreAbove(2048)))
                                .properties("publishedDate", p -> p.keyword(k -> k.ignoreAbove(32)))
                                .properties("format", p -> p.keyword(k -> k.ignoreAbove(32)))
                                .properties("status", p -> p.keyword(k -> k.ignoreAbove(32)))
                                .properties("version", p -> p.long_(n -> n)))
                        .aliases(search.getReadAlias(), a -> a)
                        .aliases(search.getWriteAlias(), a -> a.isWriteIndex(true)));
            } else {
                ensureAlias(search.getPhysicalIndex(), search.getReadAlias(), false);
                ensureAlias(search.getPhysicalIndex(), search.getWriteAlias(), true);
            }
            ready = true;
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to initialize catalog search index", exception);
        }
    }

    private void ensureAlias(String index, String alias, boolean writeIndex) throws IOException {
        if (client.indices().existsAlias(e -> e.name(alias).index(index)).value()) {
            return;
        }
        client.indices().updateAliases(u -> u.actions(a -> a.add(add -> add
                .index(index).alias(alias).isWriteIndex(writeIndex))));
    }
}

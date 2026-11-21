package com.elibrary.catalog.infrastructure.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.VersionType;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import com.elibrary.shared.kernel.AppProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

/** 以书目聚合版本写入 ES，拒绝重复投递造成的版本回退。 */
@Component
@ConditionalOnProperty(name = "app.search.enabled", havingValue = "true")
@RequiredArgsConstructor
public class CatalogSearchIndexWriter {
    private final ElasticsearchClient client;
    private final CatalogSearchIndexManager indexManager;
    private final AppProperties properties;

    public void upsert(BookSearchDocument document) {
        indexManager.ensureIndex();
        try {
            client.index(i -> i.index(properties.getSearch().getWriteAlias())
                    .id(document.getBookId())
                    .document(document)
                    .version(document.getVersion())
                    .versionType(VersionType.ExternalGte)
                    .requireAlias(true));
        } catch (ElasticsearchException exception) {
            if (exception.status() != 409) {
                throw exception;
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to index catalog book", exception);
        }
    }

    public void upsertAll(List<BookSearchDocument> documents) {
        if (documents.isEmpty()) {
            return;
        }
        indexManager.ensureIndex();
        try {
            BulkResponse response = client.bulk(b -> {
                b.index(properties.getSearch().getWriteAlias()).requireAlias(true);
                documents.forEach(document -> b.operations(operation -> operation.index(index -> index
                        .id(document.getBookId())
                        .document(document)
                        .version(document.getVersion())
                        .versionType(VersionType.ExternalGte))));
                return b;
            });
            var failure = response.items().stream()
                    .filter(item -> item.error() != null && item.status() != 409)
                    .findFirst();
            if (failure.isPresent()) {
                throw new IllegalStateException("Catalog bulk indexing failed for " + failure.get().id()
                        + ": " + failure.get().error().reason());
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to bulk index catalog books", exception);
        }
    }

    public void setRefreshInterval(String interval) {
        indexManager.ensureIndex();
        try {
            client.indices().putSettings(p -> p
                    .index(properties.getSearch().getPhysicalIndex())
                    .settings(s -> s.refreshInterval(t -> t.time(interval))));
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to update catalog search refresh interval", exception);
        }
    }

    public void refresh() {
        try {
            client.indices().refresh(r -> r.index(properties.getSearch().getReadAlias()));
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to refresh catalog search index", exception);
        }
    }
}

package com.elibrary.catalog.infrastructure.search;

import com.elibrary.catalog.infrastructure.BookRow;
import com.elibrary.catalog.infrastructure.CatalogMapper;
import com.elibrary.shared.kernel.AppProperties;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/** 可选的一次性 MySQL 到 Elasticsearch 全量书目回填。 */
@Component
@ConditionalOnProperty(name = "app.search.enabled", havingValue = "true")
@RequiredArgsConstructor
public class CatalogSearchBackfillRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(CatalogSearchBackfillRunner.class);

    private final CatalogMapper mapper;
    private final CatalogSearchIndexManager indexManager;
    private final CatalogSearchIndexWriter writer;
    private final AppProperties properties;
    private final ObjectMapper objectMapper;

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.getSearch().isRebuildOnStartup()) {
            return;
        }
        indexManager.ensureIndex();
        writer.setRefreshInterval("-1");
        long indexed;
        try {
            long afterId = 0;
            indexed = 0;
            int batchSize = properties.getSearch().getBatchSize();
            while (true) {
                List<BookRow> rows = mapper.scanForSearch(afterId, batchSize);
                if (rows.isEmpty()) {
                    break;
                }
                writer.upsertAll(rows.stream().map(row -> BookSearchDocument.from(row, objectMapper)).toList());
                indexed += rows.size();
                afterId = rows.getLast().getBookId();
                log.info("Catalog search backfill indexed {} books", indexed);
            }
        } finally {
            writer.setRefreshInterval("1s");
        }
        writer.refresh();
        log.info("Catalog search backfill completed with {} books", indexed);
    }
}

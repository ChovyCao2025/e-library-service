package com.elibrary.catalog.infrastructure.search;

import com.elibrary.catalog.infrastructure.CatalogMapper;
import com.elibrary.shared.kernel.AppProperties;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class CatalogSearchBackfillRunnerTest {
    @Test
    void skipsElasticsearchConnectionWhenStartupRebuildIsDisabled() {
        var indexManager = mock(CatalogSearchIndexManager.class);
        var writer = mock(CatalogSearchIndexWriter.class);
        var runner = new CatalogSearchBackfillRunner(
                mock(CatalogMapper.class), indexManager, writer,
                new AppProperties(), new ObjectMapper());

        runner.run(null);

        verifyNoInteractions(indexManager, writer);
    }
}

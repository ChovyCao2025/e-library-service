package com.elibrary.catalog.infrastructure.search;

import com.elibrary.catalog.application.BookSearchQuery;
import com.elibrary.catalog.application.BookSearchUnavailableException;
import com.elibrary.catalog.domain.BookFormat;
import com.elibrary.shared.kernel.AppProperties;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

class BookSearchDocumentTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void createsDocumentFromCatalogEventPayload() throws Exception {
        var payload = objectMapper.readTree("""
                {
                  "bookId": "1000001",
                  "isbn": "9780000000001",
                  "title": "Domain-Driven Design",
                  "subtitle": null,
                  "authors": "[\\\"Eric Evans\\\"]",
                  "publisher": "Example Press",
                  "categoryId": "1",
                  "language": "en",
                  "description": "A catalog event snapshot",
                  "coverUrl": null,
                  "publishedDate": "2003-08-30",
                  "format": "EBOOK",
                  "status": "PUBLISHED"
                }
                """);

        var document = BookSearchDocument.from(payload, 7, objectMapper);

        assertThat(document.getBookId()).isEqualTo("1000001");
        assertThat(document.getBookIdSort()).isEqualTo(1_000_001L);
        assertThat(document.getTitlePrefix()).isEqualTo("domain-driven design");
        assertThat(document.getAuthors()).containsExactly("Eric Evans");
        assertThat(document.getVersion()).isEqualTo(7);
        assertThat(document.toView().getPublishedDate()).hasToString("2003-08-30");
    }

    @Test
    void reportsIndexInitializationFailureAsSearchUnavailable() {
        var indexManager = mock(CatalogSearchIndexManager.class);
        doThrow(new IllegalStateException("Elasticsearch is down"))
                .when(indexManager).ensureIndex();
        var reader = new ElasticsearchBookSearchReader(
                mock(co.elastic.clients.elasticsearch.ElasticsearchClient.class),
                indexManager,
                new AppProperties());
        var query = new BookSearchQuery(
                "domain", null, null, BookFormat.EBOOK, 0);

        assertThatThrownBy(() -> reader.search(query, 20))
                .isInstanceOf(BookSearchUnavailableException.class)
                .hasMessage("Catalog search is unavailable")
                .hasCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    void buildsFilterOnlyRequestWithoutTrackingTotalHits() {
        var reader = new ElasticsearchBookSearchReader(
                mock(co.elastic.clients.elasticsearch.ElasticsearchClient.class),
                mock(CatalogSearchIndexManager.class),
                new AppProperties());
        var query = new BookSearchQuery("domain", 1L, "en", BookFormat.EBOOK, 100L);

        var request = reader.buildRequest(query, 21);

        assertThat(request.trackTotalHits().enabled()).isFalse();
        assertThat(request.query().bool().must()).isEmpty();
        assertThat(request.query().bool().filter()).hasSize(5);
        assertThat(request.sort().getFirst().field().field()).isEqualTo("bookIdSort");
        assertThat(request.searchAfter().getFirst().longValue()).isEqualTo(100L);
    }
}

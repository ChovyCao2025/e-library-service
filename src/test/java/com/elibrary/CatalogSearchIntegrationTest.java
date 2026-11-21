package com.elibrary;

import com.elibrary.catalog.application.CatalogService;
import com.elibrary.catalog.infrastructure.CatalogMapper;
import com.elibrary.catalog.infrastructure.search.BookSearchDocument;
import com.elibrary.catalog.infrastructure.search.CatalogSearchIndexWriter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** 以真实 MySQL 与 Elasticsearch 验证回填、前缀搜索、库存组装和版本幂等。 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "app.role=api",
        "app.search.enabled=true",
        "app.search.rebuild-on-startup=true",
        "app.search.consumer-enabled=false",
        "spring.elasticsearch.socket-timeout=10s"
})
class CatalogSearchIntegrationTest {
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.7")
            .withDatabaseName("elibrary")
            .withUsername("elibrary")
            .withPassword("elibrary");

    @Container
    static final ElasticsearchContainer ELASTICSEARCH =
            new ElasticsearchContainer("docker.elastic.co/elasticsearch/elasticsearch:9.2.0")
                    .withEnv("discovery.type", "single-node")
                    .withEnv("xpack.security.enabled", "false")
                    .withEnv("ES_JAVA_OPTS", "-Xms256m -Xmx256m");

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.elasticsearch.uris", ELASTICSEARCH::getHttpHostAddress);
    }

    @Autowired CatalogService catalog;
    @Autowired CatalogMapper mapper;
    @Autowired CatalogSearchIndexWriter writer;
    @Autowired ObjectMapper objectMapper;

    @Test
    void prefixSearchUsesElasticsearchAndCombinesDatabaseStock() {
        var page = catalog.browse("E-Library Demo Book 9", null, null, null, null, 5);

        assertThat(page.getItems()).hasSize(5);
        assertThat(page.isHasMore()).isTrue();
        assertThat(page.getItems()).allSatisfy(book -> {
            assertThat(book.getTitle()).startsWith("E-Library Demo Book 9");
            assertThat(book.getAvailability().getAvailable()).isEqualTo(5);
            assertThat(book.getAvailability().getTotal()).isEqualTo(5);
        });
    }

    @Test
    void externalVersionRejectsOlderProjection() {
        var row = mapper.findById(1_000_001L);
        row.setTitle("Versioned Search Title");
        row.setVersion(2);
        writer.upsert(BookSearchDocument.from(row, objectMapper));

        row.setTitle("Stale Search Title");
        row.setVersion(1);
        writer.upsert(BookSearchDocument.from(row, objectMapper));
        writer.refresh();

        var current = catalog.browse("Versioned Search", null, null, null, null, 10);
        var stale = catalog.browse("Stale Search", null, null, null, null, 10);
        assertThat(current.getItems()).extracting(book -> book.getTitle())
                .containsExactly("Versioned Search Title");
        assertThat(stale.getItems()).isEmpty();
    }
}

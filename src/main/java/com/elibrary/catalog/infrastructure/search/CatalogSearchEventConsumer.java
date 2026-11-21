package com.elibrary.catalog.infrastructure.search;

import com.elibrary.shared.kernel.AppProperties;
import com.elibrary.shared.outbox.domain.AggregateType;
import com.elibrary.shared.outbox.domain.DomainEventType;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.consumer.FilterExpression;
import org.apache.rocketmq.client.apis.consumer.SimpleConsumer;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

/** 消费 Catalog Outbox 消息，并以聚合版本幂等更新 Elasticsearch。 */
@Component
@ConditionalOnProperty(name = {"app.search.enabled", "app.search.consumer-enabled"}, havingValue = "true")
public class CatalogSearchEventConsumer implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(CatalogSearchEventConsumer.class);
    private static final Set<String> INDEXED_EVENTS = Set.of(
            DomainEventType.BOOK_CREATED.getWireValue(),
            DomainEventType.BOOK_UPDATED.getWireValue(),
            DomainEventType.BOOK_PUBLISHED.getWireValue(),
            DomainEventType.BOOK_OFFLINED.getWireValue());

    private final SimpleConsumer consumer;
    private final CatalogSearchIndexWriter writer;
    private final ObjectMapper objectMapper;
    private final AppProperties.Search properties;

    public CatalogSearchEventConsumer(AppProperties appProperties,
                                      CatalogSearchIndexWriter writer,
                                      ObjectMapper objectMapper) throws ClientException {
        this.writer = writer;
        this.objectMapper = objectMapper;
        this.properties = appProperties.getSearch();
        var provider = ClientServiceProvider.loadService();
        var configuration = ClientConfiguration.newBuilder()
                .setEndpoints(appProperties.getOutbox().getEndpoints())
                .build();
        this.consumer = provider.newSimpleConsumerBuilder()
                .setClientConfiguration(configuration)
                .setConsumerGroup(properties.getConsumerGroup())
                .setAwaitDuration(properties.getAwaitDuration())
                .setSubscriptionExpressions(Map.of(
                        AggregateType.BOOK.getTopic(), FilterExpression.SUB_ALL))
                .build();
    }

    @Scheduled(fixedDelayString = "${app.search.poll-interval:1s}")
    public void consume() {
        try {
            for (MessageView message : consumer.receive(properties.getBatchSize(),
                    properties.getInvisibleDuration())) {
                consumeOne(message);
            }
        } catch (ClientException exception) {
            log.warn("Unable to receive catalog search events: {}", exception.getMessage());
        }
    }

    private void consumeOne(MessageView message) {
        try {
            JsonNode envelope = objectMapper.readTree(readBody(message.getBody()));
            String type = envelope.path("type").asText();
            if (INDEXED_EVENTS.contains(type)) {
                long version = envelope.path("aggregateVersion").asLong();
                writer.upsert(BookSearchDocument.from(envelope.path("payload"), version, objectMapper));
            }
            consumer.ack(message);
        } catch (Exception exception) {
            log.warn("Unable to project catalog event messageId={} attempt={}: {}",
                    message.getMessageId(), message.getDeliveryAttempt(), exception.getMessage());
        }
    }

    private static String readBody(ByteBuffer source) {
        ByteBuffer body = source.asReadOnlyBuffer();
        byte[] bytes = new byte[body.remaining()];
        body.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Override
    public void close() throws IOException {
        consumer.close();
    }
}

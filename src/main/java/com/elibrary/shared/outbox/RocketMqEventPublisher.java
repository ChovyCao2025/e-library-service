package com.elibrary.shared.outbox;

import com.elibrary.shared.kernel.AppProperties;
import tools.jackson.databind.ObjectMapper;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.message.Message;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;

/**
 * RocketMQ 事件發布器。信封保留穩定 eventId、聚合版本與發生時間，
 * 讓消費者可以去重並拒絕過期版本。
 */
@Component
@ConditionalOnProperty(name = "app.outbox.rocketmq-enabled", havingValue = "true")
public class RocketMqEventPublisher implements EventPublisher, AutoCloseable {
    private final ClientServiceProvider provider;
    private final Producer producer;
    private final ObjectMapper objectMapper;

    public RocketMqEventPublisher(AppProperties properties, ObjectMapper objectMapper) throws ClientException {
        this.objectMapper = objectMapper;
        this.provider = ClientServiceProvider.loadService();
        var configuration = ClientConfiguration.newBuilder()
                .setEndpoints(properties.outbox().endpoints())
                .build();
        this.producer = provider.newProducerBuilder()
                .setClientConfiguration(configuration)
                .setTopics("catalog-book", "lending-loan", "lending-stock")
                .build();
    }

    @Override
    public void publish(OutboxEventRow event) throws Exception {
        var envelope = new LinkedHashMap<String, Object>();
        envelope.put("eventId", Long.toUnsignedString(event.id()));
        envelope.put("type", event.eventType());
        envelope.put("aggregateType", event.aggregateType());
        envelope.put("aggregateId", Long.toUnsignedString(event.aggregateId()));
        envelope.put("aggregateVersion", event.aggregateVersion());
        envelope.put("occurredAt", event.createdAt().toInstant(ZoneOffset.UTC));
        envelope.put("payload", objectMapper.readTree(event.payload()));
        Message message = provider.newMessageBuilder()
                .setTopic(topic(event))
                .setTag(event.eventType())
                // RocketMQ key 與 Outbox eventId 一致，便於追蹤和人工重放。
                .setKeys(Long.toUnsignedString(event.id()))
                .setBody(objectMapper.writeValueAsString(envelope).getBytes(StandardCharsets.UTF_8))
                .build();
        producer.send(message);
    }

    private static String topic(OutboxEventRow event) {
        if ("Book".equals(event.aggregateType())) return "catalog-book";
        if ("LendingStock".equals(event.aggregateType())) return "lending-stock";
        return "lending-loan";
    }

    @Override
    public void close() throws Exception {
        producer.close();
    }
}

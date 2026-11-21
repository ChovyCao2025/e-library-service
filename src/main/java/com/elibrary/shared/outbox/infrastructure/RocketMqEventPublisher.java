package com.elibrary.shared.outbox.infrastructure;

import com.elibrary.shared.kernel.AppProperties;
import com.elibrary.shared.outbox.application.EventPublisher;
import com.elibrary.shared.outbox.domain.AggregateType;
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
import java.util.Arrays;
import java.util.LinkedHashMap;

/**
 * RocketMQ 事件发布器。事件信封保留稳定 eventId、聚合版本与发生时间，
 * 让消费者可以去重并拒绝过期版本。
 *
 * <p>Outbox 使用至少一次投递：同一个 eventId 可能被重复发送。消费者应以 eventId
 * 建立唯一消费记录，不能依赖 RocketMQ Key 自动完成业务幂等。</p>
 */
@Component
@ConditionalOnProperty(name = OutboxConfiguration.ROCKETMQ_ENABLED_PROPERTY, havingValue = "true")
public class RocketMqEventPublisher implements EventPublisher, AutoCloseable {
    private final ClientServiceProvider provider;
    private final Producer producer;
    private final ObjectMapper objectMapper;

    public RocketMqEventPublisher(AppProperties properties, ObjectMapper objectMapper) throws ClientException {
        this.objectMapper = objectMapper;
        this.provider = ClientServiceProvider.loadService();
        var configuration = ClientConfiguration.newBuilder()
                .setEndpoints(properties.getOutbox().getEndpoints())
                .build();
        this.producer = provider.newProducerBuilder()
                .setClientConfiguration(configuration)
                .setTopics(Arrays.stream(AggregateType.values())
                        .map(AggregateType::getTopic)
                        .toArray(String[]::new))
                .build();
    }

    /** 将 Outbox 数据包装成稳定事件信封，并根据聚合类型选择 RocketMQ Topic。 */
    @Override
    public void publish(OutboxEventRow event) throws Exception {
        var envelope = new LinkedHashMap<String, Object>();
        envelope.put("eventId", Long.toUnsignedString(event.getId()));
        envelope.put("type", event.getEventType());
        envelope.put("aggregateType", event.getAggregateType());
        envelope.put("aggregateId", Long.toUnsignedString(event.getAggregateId()));
        envelope.put("aggregateVersion", event.getAggregateVersion());
        envelope.put("occurredAt", event.getCreatedAt().toInstant(ZoneOffset.UTC));
        envelope.put("payload", objectMapper.readTree(event.getPayload()));
        Message message = provider.newMessageBuilder()
                .setTopic(topic(event))
                .setTag(event.getEventType())
                // RocketMQ Key 与 Outbox eventId 一致，便于追踪、人工重放和消费者去重。
                .setKeys(Long.toUnsignedString(event.getId()))
                .setBody(objectMapper.writeValueAsString(envelope).getBytes(StandardCharsets.UTF_8))
                .build();
        producer.send(message);
    }

    /** 将书目、借阅库存与 Loan 事件分流到各自 Topic。 */
    private static String topic(OutboxEventRow event) {
        return AggregateType.fromWireValue(event.getAggregateType()).getTopic();
    }

    /** 应用程式关闭时释放 RocketMQ Producer 的网路资源。 */
    @Override
    public void close() throws Exception {
        producer.close();
    }
}

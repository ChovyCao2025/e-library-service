package com.elibrary.shared.outbox.infrastructure;

import com.elibrary.shared.outbox.application.EventPublisher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 开发环境使用的事件发布器，只记录投递资讯。
 * 关闭 RocketMQ 时仍可完整执行 Outbox 认领与确认流程。
 */
@Component
@ConditionalOnProperty(name = OutboxConfiguration.ROCKETMQ_ENABLED_PROPERTY,
        havingValue = "false", matchIfMissing = true)
public class LoggingEventPublisher implements EventPublisher {
    private static final Logger log = LoggerFactory.getLogger(LoggingEventPublisher.class);

    /** 将事件识别码与聚合资讯写入应用程式日志。 */
    @Override
    public void publish(OutboxEventRow event) {
        log.info("Development outbox delivery eventId={} type={} aggregateId={}",
                event.getId(), event.getEventType(), event.getAggregateId());
    }
}

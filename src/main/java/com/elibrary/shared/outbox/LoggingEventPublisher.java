package com.elibrary.shared.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.outbox.rocketmq-enabled", havingValue = "false", matchIfMissing = true)
public class LoggingEventPublisher implements EventPublisher {
    private static final Logger log = LoggerFactory.getLogger(LoggingEventPublisher.class);

    @Override
    public void publish(OutboxEventRow event) {
        log.info("Development outbox delivery eventId={} type={} aggregateId={}",
                event.id(), event.eventType(), event.aggregateId());
    }
}

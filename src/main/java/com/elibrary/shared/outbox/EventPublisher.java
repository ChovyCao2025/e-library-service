package com.elibrary.shared.outbox;

public interface EventPublisher {
    void publish(OutboxEventRow event) throws Exception;
}

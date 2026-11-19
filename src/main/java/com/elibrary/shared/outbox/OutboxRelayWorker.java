package com.elibrary.shared.outbox;

import com.elibrary.shared.kernel.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 以至少一次語義投遞已提交的 Outbox 事件。訊息可能在成功送出、狀態回寫前重複，
 * 因此 eventId 是下游消費者的冪等識別碼。
 */
@Component
public class OutboxRelayWorker {
    private static final Logger log = LoggerFactory.getLogger(OutboxRelayWorker.class);
    private final OutboxService service;
    private final EventPublisher publisher;
    private final AppProperties properties;

    public OutboxRelayWorker(OutboxService service, EventPublisher publisher, AppProperties properties) {
        this.service = service;
        this.publisher = publisher;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${app.outbox.interval:200ms}")
    public void relay() {
        if (!workerEnabled()) return;
        String owner = UUID.randomUUID().toString();
        for (OutboxEventRow event : service.claim(owner)) {
            try {
                // 發送位於認領交易之外，避免外部延遲延長資料庫持鎖時間。
                publisher.publish(event);
                service.sent(event.id(), owner);
            } catch (Exception exception) {
                log.warn("Outbox delivery failed for {}: {}", event.id(), exception.getMessage());
                service.failed(event.id(), owner, event.attempts(), exception.getMessage());
            }
        }
    }

    private boolean workerEnabled() {
        return "all".equalsIgnoreCase(properties.role()) || "worker".equalsIgnoreCase(properties.role());
    }
}

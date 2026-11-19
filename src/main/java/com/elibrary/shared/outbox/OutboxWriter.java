package com.elibrary.shared.outbox;

import com.elibrary.shared.kernel.ClockProvider;
import com.elibrary.shared.kernel.IdGenerator;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;

/**
 * 在呼叫端的聚合交易中附加 Outbox 事件。本元件不開啟新交易，
 * 讓業務異動與事件寫入共同提交或共同回滾。
 */
@Component
public class OutboxWriter {
    private final OutboxMapper mapper;
    private final IdGenerator ids;
    private final ClockProvider clock;
    private final ObjectMapper objectMapper;

    public OutboxWriter(OutboxMapper mapper, IdGenerator ids, ClockProvider clock, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.ids = ids;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    public long append(String aggregateType, long aggregateId, String eventType,
                       long aggregateVersion, Map<String, ?> payload, boolean searchDelivery) {
        long eventId = ids.nextId();
        var now = LocalDateTime.ofInstant(clock.now(), ZoneOffset.UTC);
        try {
            mapper.insert(eventId, aggregateType, aggregateId, eventType, aggregateVersion,
                    objectMapper.writeValueAsString(payload), now);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Unable to serialize outbox event", exception);
        }
        if (searchDelivery) {
            // 寫入當下即固定所有有效搜尋索引目標，支援雙寫切換期間的追蹤。
            mapper.insertSearchDeliveries(eventId, now);
        }
        return eventId;
    }
}

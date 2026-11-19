package com.elibrary.shared.outbox;

import com.elibrary.shared.kernel.AppProperties;
import com.elibrary.shared.kernel.ClockProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 提供 Outbox Relay 所需的短交易。認領與結果回寫各自提交，
 * 訊息代理的網路呼叫不會占用資料庫交易與資料列鎖。
 */
@Service
public class OutboxService {
    private final OutboxMapper mapper;
    private final ClockProvider clock;
    private final AppProperties.Outbox properties;

    public OutboxService(OutboxMapper mapper, ClockProvider clock, AppProperties appProperties) {
        this.mapper = mapper;
        this.clock = clock;
        this.properties = appProperties.outbox();
    }

    @Transactional(timeout = 3)
    public List<OutboxEventRow> claim(String owner) {
        var now = nowDb();
        // SKIP LOCKED 允許多個 Relay 實例分批認領，租約則支援失聯後接管。
        var ready = mapper.lockReady(now, properties.batchSize());
        var lease = now.plus(properties.lease());
        ready.forEach(event -> mapper.claim(event.id(), owner, lease));
        return mapper.findClaimed(owner);
    }

    @Transactional(timeout = 3)
    public void sent(long eventId, String owner) {
        mapper.markSent(eventId, owner, nowDb());
    }

    @Transactional(timeout = 3)
    public void failed(long eventId, String owner, int attempts, String error) {
        // 指數退避上限為五分鐘，避免故障中的訊息代理被高頻重試壓垮。
        long seconds = Math.min(300, 1L << Math.min(attempts, 8));
        mapper.reschedule(eventId, owner, nowDb().plusSeconds(seconds), truncate(error));
    }

    private LocalDateTime nowDb() { return LocalDateTime.ofInstant(clock.now(), ZoneOffset.UTC); }
    private static String truncate(String value) { return value == null ? null : value.substring(0, Math.min(1000, value.length())); }
}

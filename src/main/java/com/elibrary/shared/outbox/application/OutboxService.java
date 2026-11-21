package com.elibrary.shared.outbox.application;

import com.elibrary.shared.kernel.AppProperties;
import com.elibrary.shared.kernel.ClockProvider;
import com.elibrary.shared.outbox.domain.OutboxStatus;
import com.elibrary.shared.outbox.infrastructure.OutboxEventRow;
import com.elibrary.shared.outbox.infrastructure.OutboxMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 提供 Outbox 投递流程所需的短事务。认领与结果回写分别提交，
 * MQ 网络调用不会占用数据库事务与数据行锁。
 */
@Service
public class OutboxService {
    private final OutboxMapper mapper;
    private final ClockProvider clock;
    private final AppProperties.Outbox properties;

    public OutboxService(OutboxMapper mapper, ClockProvider clock, AppProperties appProperties) {
        this.mapper = mapper;
        this.clock = clock;
        this.properties = appProperties.getOutbox();
    }

    /**
     * 以租约批量认领当前可投递事件，让多个补偿实例可以并行工作。
     * 同一批次使用相同 owner，便于一次查询取回该批次成功认领的事件。
     */
    @Transactional(timeout = 3)
    public List<OutboxEventRow> claimBatch(String owner) {
        var now = nowDb();
        // SKIP LOCKED 让其他实例跳过本事务已锁定的数据行；租约负责 Worker 失联后的接管。
        var ready = mapper.lockReady(
                OutboxStatus.NEW, OutboxStatus.CLAIMED, now, properties.getBatchSize());
        var lease = now.plus(properties.getLease());
        ready.forEach(event -> mapper.claim(
                event.getId(), owner, lease, OutboxStatus.CLAIMED));
        return mapper.findClaimed(owner, OutboxStatus.CLAIMED);
    }

    /**
     * 通过原子条件更新认领指定事件。提交后直发与 XXL-JOB 补偿同时执行时，
     * 只有成功写入 owner 租约令牌的一方可以取得事件内容并执行投递。
     *
     * <p>可认领条件为：事件处于到达重试时间的 NEW 状态，或者 CLAIMED 租约已经过期。
     * {@code changed == 0} 表示事件已发送、尚未到重试时间，或正由其他 Worker 处理。</p>
     */
    @Transactional(timeout = 3)
    public OutboxEventRow claimOne(long eventId, String owner) {
        var now = nowDb();
        int changed = mapper.claimReady(eventId, owner, now, now.plus(properties.getLease()),
                OutboxStatus.NEW, OutboxStatus.CLAIMED, OutboxStatus.CLAIMED);
        return changed == 1
                ? mapper.findClaimedById(eventId, owner, OutboxStatus.CLAIMED)
                : null;
    }

    /**
     * 仅允许持有当前 owner 租约令牌的 Worker 将事件标记为 SENT。
     * 如果租约已经被其他 Worker 接管，旧 Worker 的条件更新会失败，避免覆盖新租约状态。
     */
    @Transactional(timeout = 3)
    public void sent(long eventId, String owner) {
        if (mapper.markSent(eventId, owner,
                OutboxStatus.CLAIMED, OutboxStatus.SENT, nowDb()) != 1) {
            throw new IllegalStateException("Outbox claim is no longer owned: " + eventId);
        }
    }

    /**
     * 记录发布失败原因，将事件恢复为 NEW，并根据当前尝试次数安排下一次投递时间。
     * SQL 同样校验 owner，租约已被接管时旧 Worker 无法修改事件。
     */
    @Transactional(timeout = 3)
    public void failed(long eventId, String owner, int attempts, String error) {
        // 指数退避上限为五分钟，避免 MQ 故障期间被高频重试持续施压。
        long seconds = Math.min(300, 1L << Math.min(attempts, 8));
        mapper.reschedule(eventId, owner, OutboxStatus.CLAIMED, OutboxStatus.NEW,
                nowDb().plusSeconds(seconds), truncate(error));
    }

    private LocalDateTime nowDb() { return LocalDateTime.ofInstant(clock.now(), ZoneOffset.UTC); }
    private static String truncate(String value) { return value == null ? null : value.substring(0, Math.min(1000, value.length())); }
}

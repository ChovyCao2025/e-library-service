package com.elibrary.shared.outbox.application;

import com.elibrary.shared.kernel.ClockProvider;
import com.elibrary.shared.kernel.SnowflakeIdGenerator;
import com.elibrary.shared.outbox.domain.AggregateType;
import com.elibrary.shared.outbox.domain.DomainEventType;
import com.elibrary.shared.outbox.domain.OutboxStatus;
import com.elibrary.shared.outbox.infrastructure.OutboxMapper;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;

/**
 * 在调用端的业务事务中追加 Outbox 事件。本组件不新建事务，
 * 因此业务数据与事件记录必然共同提交或共同回滚。
 *
 * <p>事务提交后只触发一次快速投递尝试；应用在提交后、触发前宕机时，
 * 数据库中的 NEW 事件仍会由 XXL-JOB 补偿任务扫描并投递。</p>
 */
@Component
@RequiredArgsConstructor
public class OutboxWriter {
    private final OutboxMapper mapper;
    private final SnowflakeIdGenerator idGenerator;
    private final ClockProvider clock;
    private final ObjectMapper objectMapper;
    private final OutboxAfterCommitDispatcher dispatcher;

    /**
     * 将领域事件序列化后写入 Outbox；方法会加入调用端已有的数据库事务。
     *
     * @return 新事件识别码
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public long append(AggregateType aggregateType, long aggregateId, DomainEventType eventType,
                       long aggregateVersion, Map<String, ?> payload) {
        long eventId = idGenerator.nextId();
        var now = LocalDateTime.ofInstant(clock.now(), ZoneOffset.UTC);
        try {
            mapper.insert(eventId, aggregateType.getWireValue(), aggregateId, eventType.getWireValue(),
                    aggregateVersion, objectMapper.writeValueAsString(payload), OutboxStatus.NEW, now);
            // 回调只在外层业务事务成功提交后执行，确保快速投递可以读取到完整事件。
            // 回调只传递 eventId，不在提交线程内执行数据库认领或 MQ 网络调用。
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    dispatcher.enqueue(eventId);
                }
            });
        } catch (JacksonException exception) {
            throw new IllegalStateException("Unable to serialize outbox event", exception);
        }
        return eventId;
    }
}

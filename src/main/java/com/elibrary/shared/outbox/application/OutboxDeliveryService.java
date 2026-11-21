package com.elibrary.shared.outbox.application;

import com.elibrary.shared.outbox.infrastructure.OutboxEventRow;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 统一处理 Outbox 的认领、发布与结果回写流程。提交后直发与 XXL-JOB 补偿
 * 共用本服务，确保两条触发路径遵守相同的至少一次投递语义。
 *
 * <p>数据库认领可以避免有效租约期间的并发投递，但无法把 MQ 发送与数据库回写
 * 合并为一个原子操作。如果消息发送成功后进程宕机，租约过期后仍会再次发送，
 * 因此消费者必须使用稳定的 eventId 去重。</p>
 */
@Component
@RequiredArgsConstructor
public class OutboxDeliveryService {
    private static final Logger log = LoggerFactory.getLogger(OutboxDeliveryService.class);

    private final OutboxService outboxService;
    private final EventPublisher publisher;

    /**
     * 由提交后快速路径认领并投递刚完成事务的单条事件。
     * 每次尝试生成独立租约令牌；认领失败表示其他 Worker 已持有有效租约或事件已发送。
     */
    public boolean deliverOne(long eventId) {
        // owner 标识本次投递尝试，不是业务幂等键；稳定的 eventId 才用于消息去重。
        String owner = owner(DeliverySource.DIRECT);
        // 认领事件
        OutboxEventRow event = outboxService.claimOne(eventId, owner);
        // 发送事件
        return event != null && deliverClaimed(event, owner);
    }

    /**
     * 由补偿任务使用同一个批次租约令牌认领一批到期事件。
     * 每条事件独立发布和回写，一条失败不会中断同批次其他事件。
     */
    public int deliverReadyBatch() {
        String owner = owner(DeliverySource.COMPENSATION);
        var events = outboxService.claimBatch(owner);
        events.forEach(event -> deliverClaimed(event, owner));
        return events.size();
    }

    /**
     * 在数据库认领事务提交后调用 MQ，避免网络等待长期占用数据库连接与行锁。
     * 发布成功后回写 SENT;发布失败则改回 NEW 并设置下一次尝试时间。
     */
    private boolean deliverClaimed(OutboxEventRow event, String owner) {
        try {
            publisher.publish(event);
            outboxService.sent(event.getId(), owner);
            return true;
        } catch (Exception exception) {
            log.warn("Outbox delivery failed for {}: {}", event.getId(), exception.getMessage());
            reschedule(event, owner, exception);
            return false;
        }
    }

    /**
     * 记录发布失败并安排重试。失败结果回写成功时会清除租约并恢复为 NEW；
     * 如果失败结果也无法写入数据库，则原 CLAIMED 租约保留到过期后再由补偿任务接管。
     */
    private void reschedule(OutboxEventRow event, String owner, Exception deliveryFailure) {
        try {
            outboxService.failed(event.getId(), owner, event.getAttempts(), deliveryFailure.getMessage());
        } catch (RuntimeException persistenceFailure) {
            log.error("Unable to reschedule outbox event {}", event.getId(), persistenceFailure);
        }
    }

    /**
     * 为每次认领生成唯一租约令牌。来源前缀便于排查直发或补偿路径，
     * UUID 用于隔离并发尝试和租约过期后的旧 Worker，不能替代消费者幂等处理。
     */
    private static String owner(DeliverySource source) {
        return source.name().toLowerCase(java.util.Locale.ROOT) + "-" + UUID.randomUUID();
    }

    /** 区分提交后快速投递与定时补偿产生的租约令牌来源。 */
    private enum DeliverySource {
        DIRECT,
        COMPENSATION
    }
}

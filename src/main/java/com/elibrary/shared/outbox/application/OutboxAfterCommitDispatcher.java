package com.elibrary.shared.outbox.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;

/**
 * 在业务事务提交后，将 eventId 交给专用线程池执行快速投递。
 * 本地有界队列只保存投递任务，不保存消息正文，也不替代数据库 Outbox。
 */
@Component
public class OutboxAfterCommitDispatcher {
    public static final String DIRECT_EXECUTOR_BEAN = "outboxDirectExecutor";

    private static final Logger log = LoggerFactory.getLogger(OutboxAfterCommitDispatcher.class);

    private final Executor executor;
    private final OutboxDeliveryService deliveryService;

    public OutboxAfterCommitDispatcher(
            @Qualifier(DIRECT_EXECUTOR_BEAN) Executor executor,
            OutboxDeliveryService deliveryService) {
        this.executor = executor;
        this.deliveryService = deliveryService;
    }

    /**
     * 异步提交指定事件的快速投递任务。
     *
     * <p>线程池队列已满或正在关闭时，{@code execute} 会拒绝任务。此处只记录日志，
     * 不向业务请求抛出异常，因为业务事务已经提交；数据库中的事件仍保持 NEW，
     * 后续由 XXL-JOB 补偿任务认领。</p>
     *
     * @param eventId 已提交的 Outbox 事件标识码
     */
    public void enqueue(long eventId) {
        try {
            executor.execute(() -> deliver(eventId));
        } catch (RuntimeException exception) {
            log.warn("Unable to enqueue outbox event {}, event remains pending", eventId, exception);
        }
    }

    /**
     * 执行单条事件的快速投递，并隔离异步线程中的数据库异常。
     * 未完成的事件会保持 NEW，或保持 CLAIMED 直到租约过期，随后可由补偿任务接管。
     */
    private void deliver(long eventId) {
        try {
            deliveryService.deliverOne(eventId);
        } catch (RuntimeException exception) {
            log.error("Unexpected outbox direct delivery failure for {}", eventId, exception);
        }
    }
}

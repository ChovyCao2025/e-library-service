package com.elibrary.shared.outbox.application;

import com.elibrary.shared.outbox.infrastructure.OutboxEventRow;

/** 将已提交的 Outbox 事件投递至外部讯息系统的连接埠。 */
public interface EventPublisher {
    /**
     * 投递单笔事件。发生例外时由共用投递服务保留事件并安排重试。
     *
     * @param event 已由目前直发或补偿执行器认领的事件
     */
    void publish(OutboxEventRow event) throws Exception;
}

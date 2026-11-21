package com.elibrary.shared.outbox.infrastructure;

import com.elibrary.shared.outbox.domain.OutboxStatus;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** 待投递 Outbox 事件及其认领状态的持久化模型。 */
@Getter
@Setter
@NoArgsConstructor
public class OutboxEventRow {
    // 事件识别、来源聚合及其版本。
    private long id;
    private String aggregateType;
    private long aggregateId;
    private String eventType;
    private long aggregateVersion;
    private String payload;

    // 直发或补偿执行器的认领租约、重试排程与投递状态。
    private OutboxStatus status;
    private String ownerToken;
    private LocalDateTime leaseUntil;
    private LocalDateTime nextAttemptAt;
    private int attempts;

    // 稽核时间与最后一次失败摘要。
    private LocalDateTime createdAt;
    private LocalDateTime sentAt;
    private String lastError;
}

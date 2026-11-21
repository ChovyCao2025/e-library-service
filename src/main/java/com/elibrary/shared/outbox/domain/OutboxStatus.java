package com.elibrary.shared.outbox.domain;

/** Outbox 事件从待处理到完成投递的持久化状态。 */
public enum OutboxStatus {
    NEW,
    CLAIMED,
    SENT
}

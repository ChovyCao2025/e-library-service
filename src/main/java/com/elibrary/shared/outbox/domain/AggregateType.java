package com.elibrary.shared.outbox.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

/** Outbox 事件来源聚合与其 RocketMQ Topic 契约。 */
@Getter
@RequiredArgsConstructor
public enum AggregateType {
    BOOK("Book", "catalog-book"),
    LOAN("Loan", "lending-loan"),
    LENDING_STOCK("LendingStock", "lending-stock");

    private final String wireValue;
    private final String topic;

    /** 依资料库保存的稳定值还原聚合类型。 */
    public static AggregateType fromWireValue(String value) {
        return Arrays.stream(values())
                .filter(type -> type.wireValue.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown aggregate type: " + value));
    }
}

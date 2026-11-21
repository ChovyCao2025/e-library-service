package com.elibrary.lending.application;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 搜索结果组装所需的不可变库存值，不向 Catalog 暴露 Lending 持久化对象。 */
@Getter
@RequiredArgsConstructor
public final class StockSnapshot {
    private final long bookId;
    private final int totalCopies;
    private final int availableCopies;
    private final long version;
}

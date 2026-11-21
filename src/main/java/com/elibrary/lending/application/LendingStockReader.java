package com.elibrary.lending.application;

import com.elibrary.lending.infrastructure.LendingMapper;
import com.elibrary.lending.infrastructure.LendingStockRow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 批量读取 Lending 所有的权威库存快照，供跨上下文查询组装使用。 */
@Service
@RequiredArgsConstructor
public class LendingStockReader {
    private final LendingMapper mapper;

    @Transactional(readOnly = true)
    public Map<Long, StockSnapshot> findByBookIds(List<Long> bookIds) {
        if (bookIds.isEmpty()) {
            return Map.of();
        }
        return mapper.findStocks(bookIds).stream()
                .map(StockSnapshot::from)
                .collect(Collectors.toMap(StockSnapshot::bookId, Function.identity()));
    }

    /** 搜索结果组装所需的稳定库存值，不向 Catalog 暴露 Lending 持久化对象。 */
    public record StockSnapshot(long bookId, int totalCopies, int availableCopies, long version) {
        private static StockSnapshot from(LendingStockRow row) {
            return new StockSnapshot(row.getBookId(), row.getTotalCopies(),
                    row.getAvailableCopies(), row.getVersion());
        }
    }
}

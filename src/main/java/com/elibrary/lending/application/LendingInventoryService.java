package com.elibrary.lending.application;

import com.elibrary.lending.infrastructure.LendingMapper;
import com.elibrary.lending.infrastructure.LendingStockRow;
import com.elibrary.shared.kernel.ClockProvider;
import com.elibrary.shared.outbox.application.OutboxWriter;
import com.elibrary.shared.outbox.domain.AggregateType;
import com.elibrary.shared.outbox.domain.DomainEventType;
import com.elibrary.shared.web.api.ErrorCode;
import com.elibrary.shared.web.error.DomainException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;

/**
 * 管理 Lending 边界内的书籍借阅容量。
 * 本服务由跨模组用例呼叫，并强制加入呼叫端既有的 MySQL 交易，
 * 使书目、库存与 Outbox 事件共同提交或共同回滚。
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
@RequiredArgsConstructor
public class LendingInventoryService {
    private final LendingMapper mapper;
    private final OutboxWriter outbox;
    private final ClockProvider clock;

    /**
     * 为新书建立零容量的权威库存。
     * 初始容量由管理员后续明确设定，避免书目建立后立即产生可借席位。
     */
    public void initializeForNewBook(long bookId) {
        var now = nowDb();
        if (mapper.initializeStock(bookId, now) != 1) {
            throw new IllegalStateException("Unable to initialize lending inventory for book " + bookId);
        }
    }

    /**
     * 调整借阅总容量并保留目前已借出的数量。
     * 条件更新会拒绝低于已借出数量的容量。
     */
    public void resize(long bookId, int newTotal) {
        var now = nowDb();
        if (mapper.resizeStock(bookId, newTotal, now) != 1) {
            throw DomainException.of(ErrorCode.STOCK_BELOW_BORROWED);
        }

        LendingStockRow stock = mapper.findStock(bookId);
        if (stock == null) {
            throw new IllegalStateException("Lending stock is missing for book " + bookId);
        }
        writeStockResizedEvent(stock);
    }

    /** 将调整后的完整库存快照写入 Outbox，供下游依版本处理。 */
    private void writeStockResizedEvent(LendingStockRow stock) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("bookId", Long.toUnsignedString(stock.getBookId()));
        payload.put("totalCopies", stock.getTotalCopies());
        payload.put("availableCopies", stock.getAvailableCopies());
        payload.put("stockVersion", stock.getVersion());
        outbox.append(AggregateType.LENDING_STOCK, stock.getBookId(), DomainEventType.STOCK_RESIZED,
                stock.getVersion(), payload);
    }

    private LocalDateTime nowDb() {
        return LocalDateTime.ofInstant(clock.now(), ZoneOffset.UTC);
    }
}

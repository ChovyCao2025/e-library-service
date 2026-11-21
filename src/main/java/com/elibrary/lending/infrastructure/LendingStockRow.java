package com.elibrary.lending.infrastructure;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** 借阅容量、目前可用席位与版本的持久化查询模型。 */
@Getter
@Setter
@NoArgsConstructor
public class LendingStockRow {
    /** 对应书目的识别码。 */
    private long bookId;

    /** 管理员设定的借阅总容量。 */
    private int totalCopies;

    /** 扣除目前有效借阅后的可用容量。 */
    private int availableCopies;

    /** 每次容量或借还异动后递增的乐观版本。 */
    private long version;

    /** 最近一次异动的 UTC 资料库时间。 */
    private LocalDateTime updatedAt;
}
